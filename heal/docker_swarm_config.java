version: '3.8'

networks:
  selenium-grid:
    driver: overlay
    attachable: true
    driver_opts:
      encrypted: "false"

volumes:
  selenium-videos:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${PWD}/src/test/resources/videos

services:
  # ===== SELENIUM HUB =====
  selenium-hub:
    image: selenium/hub:4.20.0-20240425
    ports:
      - "4444:4444"
      - "4442:4442"
      - "4443:4443"
    environment:
      - SE_GRID_MAX_SESSION=4
      - SE_GRID_SESSION_TIMEOUT=300
      - SE_GRID_NEW_SESSION_WAIT_TIMEOUT=60
      - SE_GRID_BROWSER_TIMEOUT=300
      - SE_SESSION_REQUEST_TIMEOUT=300
      - SE_SESSION_RETRY_INTERVAL=5
      - SE_ENABLE_TRACING=false
      - SE_OPTS=--log-level INFO --session-timeout 300 --session-request-timeout 300
      - JAVA_OPTS=-Xmx1g -XX:+UseContainerSupport
    networks:
      - selenium-grid
    deploy:
      replicas: 1
      restart_policy:
        condition: on-failure
        delay: 10s
        max_attempts: 3
      placement:
        constraints:
          - node.role == manager
      resources:
        limits:
          memory: 1g
        reservations:
          memory: 512m
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4444/wd/hub/status"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  # ===== CHROME NODE WITH VIDEO RECORDING =====
  chrome-node:
    image: selenium/node-chrome:4.20.0-20240425
    ports:
      - "7900:7900"  # VNC port
    environment:
      - SE_EVENT_BUS_HOST=selenium-hub
      - SE_EVENT_BUS_PUBLISH_PORT=4442
      - SE_EVENT_BUS_SUBSCRIBE_PORT=4443
      - SE_NODE_HOST={{.Node.Hostname}}-chrome
      - SE_NODE_MAX_INSTANCES=1
      - SE_NODE_MAX_SESSIONS=1
      - SE_NODE_SESSION_TIMEOUT=300
      - SE_VNC_NO_PASSWORD=1
      - SE_SCREEN_WIDTH=1920
      - SE_SCREEN_HEIGHT=1080
      - SE_SCREEN_DEPTH=24
      - START_XVFB=true
      - DBUS_SESSION_BUS_ADDRESS=/dev/null
      # Video recording settings
      - SE_RECORD_VIDEO=true
      - SE_VIDEO_UPLOAD_ON_PASSING=true
      - SE_VIDEO_UPLOAD_ON_FAILING=true
      - SE_VIDEO_FOLDER=/videos
      - SE_VIDEO_FILE_NAME=auto
      - SE_VIDEO_CODEC=libx264
      - SE_VIDEO_PRESET=ultrafast
      - SE_VIDEO_FRAMERATE=15
      # Chrome options for stability
      - SE_NODE_OVERRIDE_MAX_SESSIONS=true
      - JAVA_OPTS=-Xmx1g -XX:+UseContainerSupport
    volumes:
      - selenium-videos:/videos
      - /dev/shm:/dev/shm
    networks:
      - selenium-grid
    depends_on:
      - selenium-hub
    deploy:
      replicas: 1
      restart_policy:
        condition: on-failure
        delay: 10s
        max_attempts: 3
      resources:
        limits:
          memory: 2g
          cpus: '1.0'
        reservations:
          memory: 1g
          cpus: '0.5'
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5555/status"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  # ===== FILE BROWSER FOR VIDEO ACCESS =====
  file-browser:
    image: filebrowser/filebrowser:v2.27.0
    ports:
      - "8081:80"
    volumes:
      - selenium-videos:/srv
    environment:
      - FB_NOAUTH=true
      - FB_ROOT=/srv
      - FB_PORT=80
      - FB_ADDRESS=0.0.0.0
      - FB_LOG=stdout
    command: ["--noauth", "--root", "/srv", "--port", "80", "--address", "0.0.0.0"]
    networks:
      - selenium-grid
    deploy:
      replicas: 1
      restart_policy:
        condition: on-failure
        delay: 5s
        max_attempts: 3
      resources:
        limits:
          memory: 256m
        reservations:
          memory: 128m
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80"]
      interval: 30s
      timeout: 10s
      retries: 3

  # ===== STANDALONE VIDEO RECORDER (FALLBACK) =====
  video-recorder:
    image: selenium/video:ffmpeg-7.1-20250606
    environment:
      - DISPLAY_CONTAINER_NAME=chrome-node
      - SE_VIDEO_FILE_NAME=test-session
      - SE_VIDEO_UPLOAD_ON_PASSING=true
      - SE_VIDEO_UPLOAD_ON_FAILING=true
      - SE_VIDEO_FOLDER=/videos
      - SE_VIDEO_CODEC=libx264
      - SE_VIDEO_PRESET=ultrafast
      - SE_VIDEO_FRAMERATE=15
      - SE_VIDEO_SIZE=1920x1080
    volumes:
      - selenium-videos:/videos
    networks:
      - selenium-grid
    depends_on:
      - chrome-node
    deploy:
      replicas: 1
      restart_policy:
        condition: on-failure
        delay: 10s
        max_attempts: 2
      resources:
        limits:
          memory: 512m
        reservations:
          memory: 256m

  # ===== NGINX REVERSE PROXY (OPTIONAL) =====
  nginx-proxy:
    image: nginx:1.25-alpine
    ports:
      - "8080:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    networks:
      - selenium-grid
    depends_on:
      - selenium-hub
      - file-browser
    deploy:
      replicas: 1
      restart_policy:
        condition: on-failure
      resources:
        limits:
          memory: 128m
        reservations:
          memory: 64m

# ===== SWARM DEPLOYMENT CONFIGURATION =====
configs:
  nginx_config:
    external: true

secrets:
  selenium_credentials:
    external: true