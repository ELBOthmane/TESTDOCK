pipeline {
    agent {
        label 'linux-docker' // Use Linux agents with Docker capability
    }
    
    environment {
        // Maven Configuration
        MAVEN_OPTS = '-Xmx4g -XX:+UseG1GC -XX:+UseContainerSupport'
        
        // Docker Swarm Configuration
        DOCKER_COMPOSE_FILE = 'docker-compose.yml'
        SELENIUM_STACK_NAME = 'selenium-grid-${BUILD_NUMBER}'
        
        // Selenium Configuration
        SELENIUM_HUB_HOST = 'localhost'
        SELENIUM_HUB_URL = "http://localhost:4444/wd/hub"
        
        // Test Configuration
        TARGET_URL = 'https://www.spoticar.fr/'
        VIDEO_RECORDING_ENABLED = 'true'
        HEADLESS_MODE = 'false'
        PARALLEL_TESTS = 'true'
        THREAD_COUNT = '3'
        
        // Directories
        WORKSPACE_DIR = "${WORKSPACE}"
        VIDEO_DIR = "${WORKSPACE}/src/test/resources/videos"
        REPORTS_DIR = "${WORKSPACE}/target"
        
        // Timeouts (in seconds)
        GRID_STARTUP_TIMEOUT = '300'
        TEST_TIMEOUT = '3600'
        
        // Jenkins Integration
        JENKINS_WORKSPACE = "${WORKSPACE}"
        BUILD_DISPLAY_NAME = "${BUILD_DISPLAY_NAME}"
    }
    
    options {
        // Build options
        buildDiscarder(logRotator(
            numToKeepStr: '10',
            artifactNumToKeepStr: '5'
        ))
        timeout(time: 2, unit: 'HOURS')
        timestamps()
        ansiColor('xterm')
        
        // Concurrent builds
        disableConcurrentBuilds()
        
        // Workspace cleanup
        skipDefaultCheckout(false)
    }
    
    parameters {
        choice(
            name: 'BROWSER',
            choices: ['chrome'],
            description: 'Browser to use for testing'
        )
        booleanParam(
            name: 'HEADLESS',
            defaultValue: false,
            description: 'Run tests in headless mode'
        )
        choice(
            name: 'THREAD_COUNT',
            choices: ['1', '2', '3', '4', '5'],
            description: 'Number of parallel test threads'
        )
        booleanParam(
            name: 'CLEANUP_AFTER',
            defaultValue: true,
            description: 'Cleanup Docker resources after tests'
        )
        string(
            name: 'TEST_TAGS',
            defaultValue: '@runTheses',
            description: 'Cucumber tags to run'
        )
    }
    
    stages {
        stage('🔧 Environment Setup') {
            steps {
                script {
                    echo "🚀 Starting Selenium Test Pipeline"
                    echo "Build: ${BUILD_DISPLAY_NAME}"
                    echo "Node: ${NODE_NAME}"
                    echo "Workspace: ${WORKSPACE}"
                    
                    // Display parameters
                    echo "Parameters:"
                    echo "  - Browser: ${params.BROWSER}"
                    echo "  - Headless: ${params.HEADLESS}"
                    echo "  - Thread Count: ${params.THREAD_COUNT}"
                    echo "  - Test Tags: ${params.TEST_TAGS}"
                    echo "  - Cleanup After: ${params.CLEANUP_AFTER}"
                }
            }
        }
        
        stage('📋 System Check') {
            steps {
                script {
                    echo "🔍 Checking system requirements..."
                    
                    // Check Docker
                    sh '''
                        echo "=== Docker Version ==="
                        docker --version
                        docker compose version
                        
                        echo "=== Docker Info ==="
                        docker info | grep -E "(Server Version|Storage Driver|Kernel Version|CPUs|Total Memory)"
                        
                        echo "=== System Resources ==="
                        free -h
                        df -h .
                        
                        echo "=== Java Version ==="
                        java -version
                        
                        echo "=== Maven Version ==="
                        mvn --version
                    '''
                    
                    // Initialize Docker Swarm if needed
                    sh '''
                        if ! docker info | grep -q "Swarm: active"; then
                            echo "🐳 Initializing Docker Swarm..."
                            docker swarm init --advertise-addr 127.0.0.1 || true
                        else
                            echo "✅ Docker Swarm already active"
                        fi
                    '''
                }
            }
        }
        
        stage('📁 Workspace Preparation') {
            steps {
                script {
                    echo "📁 Preparing workspace directories..."
                    
                    sh '''
                        # Create necessary directories
                        mkdir -p "${VIDEO_DIR}"
                        mkdir -p "${REPORTS_DIR}/cucumber-reports"
                        mkdir -p "${REPORTS_DIR}/screenshots"
                        mkdir -p "${REPORTS_DIR}/allure-results"
                        
                        # Set permissions
                        chmod -R 755 "${VIDEO_DIR}"
                        
                        # Clean old files
                        find "${VIDEO_DIR}" -name "*.mp4" -type f -mtime +1 -delete 2>/dev/null || true
                        find "${REPORTS_DIR}" -name "*.json" -type f -mtime +1 -delete 2>/dev/null || true
                        
                        echo "✅ Workspace prepared"
                        ls -la "${VIDEO_DIR}"
                    '''
                }
            }
        }
        
        stage('🐳 Docker Grid Deployment') {
            steps {
                script {
                    echo "🐳 Deploying Selenium Grid with Docker Swarm..."
                    
                    // Cleanup any existing stack
                    sh '''
                        if docker stack ls | grep -q "${SELENIUM_STACK_NAME}"; then
                            echo "🧹 Cleaning up existing stack..."
                            docker stack rm "${SELENIUM_STACK_NAME}"
                            sleep 15
                        fi
                    '''
                    
                    // Deploy new stack
                    sh '''
                        echo "🚀 Deploying new Selenium Grid stack..."
                        
                        # Export environment variables for docker-compose
                        export PWD="${WORKSPACE}"
                        export VIDEO_HOST_PATH="${VIDEO_DIR}"
                        
                        # Deploy the stack
                        docker stack deploy -c "${DOCKER_COMPOSE_FILE}" "${SELENIUM_STACK_NAME}"
                        
                        echo "✅ Stack deployment initiated"
                    '''
                }
            }
        }
        
        stage('⏳ Grid Readiness Wait') {
            steps {
                script {
                    echo "⏳ Waiting for Selenium Grid to become ready..."
                    
                    timeout(time: 5, unit: 'MINUTES') {
                        sh '''
                            echo "🔍 Waiting for Selenium Hub..."
                            
                            for i in $(seq 1 30); do
                                if curl -f -s "http://localhost:4444/wd/hub/status" > /dev/null 2>&1; then
                                    echo "✅ Selenium Hub is ready"
                                    break
                                fi
                                
                                echo "⏳ Attempt $i/30: Waiting for hub..."
                                sleep 10
                            done
                            
                            # Additional wait for nodes to register
                            echo "⏳ Waiting for Chrome nodes to register..."
                            sleep 30
                            
                            # Verify grid status
                            echo "📊 Grid Status:"
                            curl -s "http://localhost:4444/wd/hub/status" | jq '.' || echo "Grid status check failed"
                            
                            # Show running services
                            echo "🐳 Running Services:"
                            docker stack services "${SELENIUM_STACK_NAME}"
                        '''
                    }
                }
            }
        }
        
        stage('🧪 Test Execution') {
            steps {
                script {
                    echo "🧪 Running Selenium tests..."
                    
                    // Set dynamic environment variables
                    env.THREAD_COUNT = params.THREAD_COUNT
                    env.HEADLESS_MODE = params.HEADLESS.toString()
                    env.TEST_TAGS = params.TEST_TAGS
                    
                    // Execute tests with proper error handling
                    def testResult = sh(
                        script: '''
                            echo "🚀 Starting test execution..."
                            echo "Configuration:"
                            echo "  - Hub URL: ${SELENIUM_HUB_URL}"
                            echo "  - Target URL: ${TARGET_URL}"
                            echo "  - Thread Count: ${THREAD_COUNT}"
                            echo "  - Headless: ${HEADLESS_MODE}"
                            echo "  - Tags: ${TEST_TAGS}"
                            
                            # Run Maven tests with Jenkins profile
                            mvn clean verify \
                                -Pjenkins \
                                -Ddocker.mode=true \
                                -Dselenium.hub.host="${SELENIUM_HUB_HOST}" \
                                -Dselenium.hub.url="${SELENIUM_HUB_URL}" \
                                -Dtarget.url="${TARGET_URL}" \
                                -Dvideo.recording.enabled="${VIDEO_RECORDING_ENABLED}" \
                                -Dheadless="${HEADLESS_MODE}" \
                                -Dparallel.tests="${PARALLEL_TESTS}" \
                                -Dthread.count="${THREAD_COUNT}" \
                                -Dcucumber.filter.tags="${TEST_TAGS}" \
                                -Dmaven.test.failure.ignore=true \
                                -Dwebdriver.timeouts.pageload=180000 \
                                -Dwebdriver.timeouts.implicit=20000 \
                                -Dwebdriver.timeouts.script=120000 \
                                -Dwebdriver.remote.http.connectiontimeout=600000 \
                                -Dwebdriver.remote.http.readtimeout=600000
                        ''',
                        returnStatus: true
                    )
                    
                    // Store test result for later use
                    env.TEST_EXECUTION_RESULT = testResult.toString()
                    
                    if (testResult != 0) {
                        echo "⚠️ Tests completed with failures (exit code: ${testResult})"
                        currentBuild.result = 'UNSTABLE'
                    } else {
                        echo "✅ All tests passed successfully"
                    }
                }
            }
        }
        
        stage('📊 Results Collection') {
            steps {
                script {
                    echo "📊 Collecting test results and artifacts..."
                    
                    // Collect test results
                    sh '''
                        echo "📁 Collecting test artifacts..."
                        
                        # List generated files
                        echo "=== Generated Reports ==="
                        find "${REPORTS_DIR}" -type f -name "*.json" -o -name "*.xml" -o -name "*.html" | head -20
                        
                        echo "=== Generated Videos ==="
                        find "${VIDEO_DIR}" -type f -name "*.mp4" | head -10
                        
                        echo "=== Screenshots ==="
                        find "${REPORTS_DIR}" -type f -name "*.png" | head -10
                        
                        # Create summary
                        echo "=== Test Summary ==="
                        if [ -f "${REPORTS_DIR}/cucumber-reports/cucumber.json" ]; then
                            echo "Cucumber JSON report found"
                            jq -r '.[] | select(.elements) | .elements[] | select(.steps) | .steps[] | select(.result.status) | .result.status' "${REPORTS_DIR}/cucumber-reports/cucumber.json" | sort | uniq -c || true
                        fi
                    '''
                }
            }
        }
        
        stage('📹 Video Processing') {
            when {
                expression { env.VIDEO_RECORDING_ENABLED == 'true' }
            }
            steps {
                script {
                    echo "📹 Processing test videos..."
                    
                    sh '''
                        cd "${VIDEO_DIR}"
                        
                        if [ "$(ls -A . 2>/dev/null)" ]; then
                            echo "📹 Found test videos:"
                            ls -lah *.mp4 2>/dev/null || echo "No MP4 files found"
                            
                            # Create video summary
                            echo "=== Video Summary ===" > video_summary.txt
                            echo "Build: ${BUILD_NUMBER}" >> video_summary.txt
                            echo "Date: $(date)" >> video_summary.txt
                            echo "Total Videos: $(ls *.mp4 2>/dev/null | wc -l)" >> video_summary.txt
                            echo "" >> video_summary.txt
                            
                            for video in *.mp4 2>/dev/null; do
                                if [ -f "$video" ]; then
                                    size=$(stat -c%s "$video" 2>/dev/null || echo "0")
                                    echo "$video - ${size} bytes" >> video_summary.txt
                                fi
                            done 2>/dev/null || true
                            
                            echo "✅ Video processing completed"
                        else
                            echo "⚠️ No videos found"
                        fi
                    '''
                }
            }
        }
    }
    
    post {
        always {
            script {
                echo "🧹 Post-build cleanup and archiving..."
                
                // Archive test results
                if (fileExists("${REPORTS_DIR}/cucumber-reports/cucumber.json")) {
                    echo "📋 Publishing Cucumber results..."
                    
                    // Cucumber reports
                    publishHTML([
                        allowMissing: false,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: "${REPORTS_DIR}",
                        reportFiles: 'cucumber-html-report.html',
                        reportName: 'Cucumber HTML Report',
                        reportTitles: 'Test Results'
                    ])
                    
                    // JUnit results
                    if (fileExists("${REPORTS_DIR}/cucumber-reports/cucumber.xml")) {
                        junit "${REPORTS_DIR}/cucumber-reports/cucumber.xml"
                    }
                }
                
                // Archive artifacts
                archiveArtifacts artifacts: '''
                    target/cucumber-reports/**/*,
                    target/screenshots/**/*,
                    target/reports/**/*,
                    src/test/resources/videos/**/*
                ''', allowEmptyArchive: true, fingerprint: true
                
                // Docker cleanup
                if (params.CLEANUP_AFTER) {
                    sh '''
                        echo "🧹 Cleaning up Docker resources..."
                        
                        # Remove the stack
                        if docker stack ls | grep -q "${SELENIUM_STACK_NAME}"; then
                            docker stack rm "${SELENIUM_STACK_NAME}"
                            echo "Stack removal initiated"
                        fi
                        
                        # Clean up dangling resources (after a delay)
                        sleep 30
                        docker system prune -f --volumes || true
                        
                        echo "✅ Docker cleanup completed"
                    '''
                } else {
                    echo "⚠️ Skipping Docker cleanup as requested"
                    sh '''
                        echo "🐳 Docker resources left running:"
                        docker stack services "${SELENIUM_STACK_NAME}" || echo "No stack found"
                    '''
                }
            }
        }
        
        success {
            script {
                echo "✅ Pipeline completed successfully!"
                
                // Success notifications
                if (env.VIDEO_RECORDING_ENABLED == 'true') {
                    sh '''
                        video_count=$(find "${VIDEO_DIR}" -name "*.mp4" | wc -l)
                        echo "📹 Recorded ${video_count} test videos"
                    '''
                }
                
                // Build summary
                def summary = """
                🎉 **Test Pipeline Successful**
                
                **Build Details:**
                - Build: ${BUILD_DISPLAY_NAME}
                - Node: ${NODE_NAME}
                - Duration: ${currentBuild.durationString}
                
                **Configuration:**
                - Browser: ${params.BROWSER}
                - Threads: ${params.THREAD_COUNT}
                - Headless: ${params.HEADLESS}
                - Tags: ${params.TEST_TAGS}
                
                **Artifacts:**
                - Test Reports: Available
                - Screenshots: Available
                - Videos: ${env.VIDEO_RECORDING_ENABLED == 'true' ? 'Available' : 'Disabled'}
                """
                
                currentBuild.description = summary
            }
        }
        
        failure {
            script {
                echo "❌ Pipeline failed!"
                
                // Collect failure information
                sh '''
                    echo "🔍 Failure Analysis:"
                    
                    echo "=== Docker Services ==="
                    docker stack services "${SELENIUM_STACK_NAME}" || echo "No stack found"
                    
                    echo "=== Recent Logs ==="
                    docker service logs --tail 50 "${SELENIUM_STACK_NAME}_selenium-hub" 2>/dev/null || echo "No hub logs"
                    docker service logs --tail 50 "${SELENIUM_STACK_NAME}_chrome-node" 2>/dev/null || echo "No node logs"
                    
                    echo "=== System Resources ==="
                    free -h
                    df -h .
                '''
                
                currentBuild.description = "❌ Pipeline failed - Check logs for details"
            }
        }
        
        unstable {
            script {
                echo "⚠️ Pipeline completed with test failures"
                
                // Analyze test failures
                if (fileExists("${REPORTS_DIR}/cucumber-reports/cucumber.json")) {
                    sh '''
                        echo "📊 Test Failure Analysis:"
                        
                        if command -v jq >/dev/null 2>&1; then
                            failed_scenarios=$(jq -r '.[] | select(.elements) | .elements[] | select(.steps) | select(.steps[].result.status == "failed") | .name' "${REPORTS_DIR}/cucumber-reports/cucumber.json" 2>/dev/null | wc -l)
                            echo "Failed scenarios: ${failed_scenarios}"
                        fi
                    '''
                }
                
                currentBuild.description = "⚠️ Tests completed with failures - Check reports"
            }
        }
    }
}