#!/bin/bash
# ============================================================================
# Docker Swarm Selenium Grid Deployment Scripts
# Compatible with WSL2, Linux VM, and Jenkins CI/CD
# ============================================================================

set -e  # Exit on any error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
STACK_NAME="selenium-grid"
COMPOSE_FILE="docker-compose.yml"
NETWORK_NAME="selenium-grid"
VIDEO_DIR="./src/test/resources/videos"

# Helper functions
log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if we're running in WSL2
check_wsl2() {
    if grep -qi microsoft /proc/version 2>/dev/null; then
        log "Running in WSL2 environment"
        return 0
    else
        log "Running in native Linux environment"
        return 1
    fi
}

# Function to check system requirements
check_requirements() {
    log "Checking system requirements..."
    
    # Check Docker
    if ! command -v docker &> /dev/null; then
        error "Docker is not installed"
        exit 1
    fi
    
    # Check Docker Compose
    if ! docker compose version &> /dev/null; then
        error "Docker Compose is not available"
        exit 1
    fi
    
    # Check Docker Swarm
    if ! docker info | grep -q "Swarm: active"; then
        warning "Docker Swarm is not initialized"
        log "Initializing Docker Swarm..."
        docker swarm init --advertise-addr 127.0.0.1 || {
            error "Failed to initialize Docker Swarm"
            exit 1
        }
    fi
    
    # Check available memory
    AVAILABLE_MEMORY=$(free -m | awk 'NR==2{printf "%.0f", $7}')
    if [ "$AVAILABLE_MEMORY" -lt 2048 ]; then
        warning "Available memory is ${AVAILABLE_MEMORY}MB. Recommended: 2GB+"
    fi
    
    # Check disk space
    AVAILABLE_DISK=$(df . | awk 'NR==2{print $4}')
    if [ "$AVAILABLE_DISK" -lt 5000000 ]; then  # 5GB in KB
        warning "Available disk space is low. Recommended: 5GB+ free space"
    fi
    
    success "System requirements check completed"
}

# Function to create necessary directories
create_directories() {
    log "Creating necessary directories..."
    
    # Create video directory with proper permissions
    mkdir -p "$VIDEO_DIR"
    chmod 755 "$VIDEO_DIR"
    
    # Create additional directories
    mkdir -p ./target/cucumber-reports
    mkdir -p ./target/screenshots
    mkdir -p ./target/reports
    
    # Set ownership for WSL2 compatibility
    if check_wsl2; then
        # In WSL2, ensure directories are accessible
        sudo chown -R $(whoami):$(whoami) "$VIDEO_DIR" 2>/dev/null || true
    fi
    
    success "Directories created successfully"
}

# Function to create nginx configuration
create_nginx_config() {
    log "Creating nginx configuration..."
    
    cat > nginx.conf << 'EOF'
events {
    worker_connections 1024;
}

http {
    upstream selenium-hub {
        server selenium-hub:4444;
    }
    
    upstream file-browser {
        server file-browser:80;
    }
    
    server {
        listen 80;
        
        location /grid/ {
            proxy_pass http://selenium-hub/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }
        
        location /files/ {
            proxy_pass http://file-browser/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }
        
        location / {
            return 301 /grid/;
        }
    }
}
EOF
    
    success "Nginx configuration created"
}

# Function to deploy the stack
deploy_stack() {
    log "Deploying Selenium Grid stack..."
    
    # Export environment variables for compose file
    export COMPOSE_PROJECT_NAME="$STACK_NAME"
    export VIDEO_HOST_PATH="$(realpath $VIDEO_DIR)"
    
    # Deploy the stack
    docker stack deploy -c "$COMPOSE_FILE" "$STACK_NAME" || {
        error "Failed to deploy stack"
        exit 1
    }
    
    success "Stack deployed successfully"
}

# Function to wait for services to be ready
wait_for_services() {
    log "Waiting for services to become ready..."
    
    local max_wait=300  # 5 minutes
    local waited=0
    
    while [ $waited -lt $max_wait ]; do
        # Check Selenium Hub
        if curl -f -s http://localhost:4444/wd/hub/status > /dev/null 2>&1; then
            success "Selenium Hub is ready"
            break
        fi
        
        log "Waiting for Selenium Hub... (${waited}s/${max_wait}s)"
        sleep 10
        waited=$((waited + 10))
    done
    
    if [ $waited -ge $max_wait ]; then
        error "Services did not become ready within ${max_wait} seconds"
        show_service_logs
        exit 1
    fi
    
    # Additional wait for Chrome node
    log "Waiting additional 30 seconds for Chrome node to register..."
    sleep 30
    
    success "All services are ready"
}

# Function to show service status
show_status() {
    log "Current service status:"
    
    echo ""
    echo "=== Docker Stack Services ==="
    docker stack services "$STACK_NAME" --format "table {{.Name}}\t{{.Replicas}}\t{{.Image}}"
    
    echo ""
    echo "=== Service Health Checks ==="
    
    # Selenium Hub
    if curl -f -s http://localhost:4444/wd/hub/status > /dev/null 2>&1; then
        success "✅ Selenium Hub (http://localhost:4444)"
    else
        error "❌ Selenium Hub (http://localhost:4444)"
    fi
    
    # File Browser
    if curl -f -s http://localhost:8081 > /dev/null 2>&1; then
        success "✅ File Browser (http://localhost:8081)"
    else
        warning "⚠️  File Browser (http://localhost:8081)"
    fi
    
    # VNC
    if nc -z localhost 7900 2>/dev/null; then
        success "✅ VNC Server (http://localhost:7900)"
    else
        warning "⚠️  VNC Server (http://localhost:7900)"
    fi
    
    echo ""
    echo "=== Grid Console ==="
    echo "Grid Console: http://localhost:4444/ui"
    echo "Grid Status:  http://localhost:4444/wd/hub/status"
    echo ""
}

# Function to show service logs
show_service_logs() {
    log "Showing recent service logs..."
    
    local services=("${STACK_NAME}_selenium-hub" "${STACK_NAME}_chrome-node" "${STACK_NAME}_file-browser")
    
    for service in "${services[@]}"; do
        echo ""
        echo "=== Logs for $service ==="
        docker service logs --tail 20 "$service" 2>/dev/null || warning "Could not fetch logs for $service"
    done
}

# Function to run tests
run_tests() {
    log "Running Selenium tests..."
    
    # Set Java system properties for Docker mode
    export MAVEN_OPTS="-Xmx4g -XX:+UseG1GC"
    
    # Run tests with Docker profile
    if command -v mvn &> /dev/null; then
        mvn clean verify \
            -Pdocker-swarm-local \
            -Ddocker.mode=true \
            -Dselenium.hub.url=http://localhost:4444/wd/hub \
            -Dvideo.recording.enabled=true \
            -Dheadless=false \
            -Dmaven.test.failure.ignore=false \
            || {
                warning "Test execution completed with failures"
                return 1
            }
        
        success "Tests completed successfully"
        
        # Show test results
        if [ -f target/cucumber-reports/cucumber.json ]; then
            log "Test results available in target/cucumber-reports/"
        fi
        
        # Show videos
        if [ -d "$VIDEO_DIR" ] && [ "$(ls -A $VIDEO_DIR)" ]; then
            log "Test videos available in $VIDEO_DIR/"
            ls -la "$VIDEO_DIR/"
        fi
        
    else
        warning "Maven not found. Please run tests manually with:"
        echo "mvn clean verify -Pdocker-swarm-local"
    fi
}

# Function to cleanup
cleanup() {
    log "Cleaning up..."
    
    # Remove the stack
    if docker stack ls | grep -q "$STACK_NAME"; then
        docker stack rm "$STACK_NAME"
        log "Stack removed. Waiting for cleanup..."
        sleep 10
    fi
    
    # Clean up networks (only if no other stacks are using them)
    if docker network ls | grep -q "${STACK_NAME}_${NETWORK_NAME}"; then
        docker network rm "${STACK_NAME}_${NETWORK_NAME}" 2>/dev/null || true
    fi
    
    # Clean up volumes (be careful - this removes video data!)
    if [ "$1" = "--remove-videos" ]; then
        warning "Removing video volumes and data..."
        docker volume prune -f
        rm -rf "$VIDEO_DIR"/*
    fi
    
    success "Cleanup completed"
}

# Function to scale services
scale_services() {
    local chrome_replicas=${1:-1}
    
    log "Scaling Chrome nodes to $chrome_replicas replicas..."
    
    docker service scale "${STACK_NAME}_chrome-node=$chrome_replicas" || {
        error "Failed to scale Chrome nodes"
        exit 1
    }
    
    success "Chrome nodes scaled to $chrome_replicas"
}

# Function to show help
show_help() {
    echo "Docker Swarm Selenium Grid Management Script"
    echo ""
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo "Commands:"
    echo "  deploy      Deploy the Selenium Grid stack"
    echo "  status      Show current service status"
    echo "  logs        Show service logs"
    echo "  test        Run Selenium tests"
    echo "  scale N     Scale Chrome nodes to N replicas"
    echo "  cleanup     Remove the stack and cleanup"
    echo "  stop        Stop all services"
    echo "  restart     Restart all services"
    echo "  help        Show this help message"
    echo ""
    echo "Options:"
    echo "  --remove-videos    With cleanup: also remove video files"
    echo ""
    echo "Examples:"
    echo "  $0 deploy                    # Deploy the grid"
    echo "  $0 status                    # Check status"
    echo "  $0 test                      # Run tests"
    echo "  $0 scale 3                   # Scale to 3 Chrome nodes"
    echo "  $0 cleanup --remove-videos   # Cleanup including videos"
    echo ""
}

# Main script logic
main() {
    case "${1:-help}" in
        deploy)
            check_requirements
            create_directories
            create_nginx_config
            deploy_stack
            wait_for_services
            show_status
            ;;
        status)
            show_status
            ;;
        logs)
            show_service_logs
            ;;
        test)
            run_tests
            ;;
        scale)
            if [ -z "$2" ]; then
                error "Please specify number of replicas: $0 scale N"
                exit 1
            fi
            scale_services "$2"
            ;;
        stop|cleanup)
            cleanup "$2"
            ;;
        restart)
            cleanup
            sleep 5
            check_requirements
            create_directories
            create_nginx_config
            deploy_stack
            wait_for_services
            show_status
            ;;
        help|*)
            show_help
            ;;
    esac
}

# Run main function with all arguments
main "$@"