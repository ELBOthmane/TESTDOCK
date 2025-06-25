package Helper;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Enhanced Video Recorder for Docker Swarm Grid with Selenium 4.20.0
 * Supports both local and Jenkins execution environments
 */
public class AutoVideoRecorder {
    
    private static final ConcurrentHashMap<String, VideoSession> testSessionMap = new ConcurrentHashMap<>();
    private static final String VIDEO_DIR = getVideoDirectory();
    private static final String VIDEO_FORMAT = "mp4";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    // Video session tracking
    private static class VideoSession {
        String sessionId;
        String testName;
        long startTime;
        String nodeHost;
        
        VideoSession(String sessionId, String testName, long startTime, String nodeHost) {
            this.sessionId = sessionId;
            this.testName = testName;
            this.startTime = startTime;
            this.nodeHost = nodeHost;
        }
    }
    
    static {
        initializeEnvironment();
    }

    /**
     * Enhanced video directory detection for different environments
     */
    private static String getVideoDirectory() {
        // 1. Jenkins environment
        String jenkinsWorkspace = System.getenv("WORKSPACE");
        if (jenkinsWorkspace != null && !jenkinsWorkspace.isEmpty()) {
            String jenkinsVideoDir = jenkinsWorkspace + File.separator + "videos";
            System.out.println("🔧 Jenkins mode - Video directory: " + jenkinsVideoDir);
            return jenkinsVideoDir;
        }
        
        // 2. Docker mode with volume mapping
        boolean isDockerMode = "true".equals(System.getProperty("docker.mode", "false"));
        if (isDockerMode) {
            String dockerVideoDir = System.getProperty("video.recording.directory");
            if (dockerVideoDir != null && !dockerVideoDir.isEmpty()) {
                System.out.println("🐳 Docker mode - Video directory: " + dockerVideoDir);
                return dockerVideoDir;
            }
        }
        
        // 3. WSL2 environment detection
        String wslDistro = System.getenv("WSL_DISTRO_NAME");
        if (wslDistro != null) {
            String projectRoot = System.getProperty("user.dir");
            String wslVideoDir = projectRoot + File.separator + "src" + File.separator + "test" + 
                               File.separator + "resources" + File.separator + "videos";
            System.out.println("🐧 WSL2 mode - Video directory: " + wslVideoDir);
            return wslVideoDir;
        }
        
        // 4. Default local development
        String projectRoot = System.getProperty("user.dir");
        String defaultVideoDir = projectRoot + File.separator + "src" + File.separator + "test" + 
                                File.separator + "resources" + File.separator + "videos";
        System.out.println("💻 Local mode - Video directory: " + defaultVideoDir);
        return defaultVideoDir;
    }

    /**
     * Enhanced recording start with better session tracking
     */
    public static synchronized boolean startRecording(String testName) {
        if (testName == null || testName.trim().isEmpty()) {
            System.err.println("❌ Test name cannot be null or empty");
            return false;
        }
        
        String normalizedTestName = normalizeTestName(testName);
        
        if (isUtilityTest(normalizedTestName)) {
            System.out.println("⚠️ Skipping video recording for utility test: " + normalizedTestName);
            return true;
        }
        
        try {
            String sessionId = null;
            String nodeHost = "unknown";
            
            // Enhanced session ID extraction
            try {
                WebDriver driver = DriverManager.getDriver();
                if (driver instanceof RemoteWebDriver) {
                    RemoteWebDriver remoteDriver = (RemoteWebDriver) driver;
                    SessionId driverSessionId = remoteDriver.getSessionId();
                    
                    if (driverSessionId != null) {
                        sessionId = driverSessionId.toString();
                        
                        // Extract node information if available
                        try {
                            Map<String, Object> capabilities = remoteDriver.getCapabilities().asMap();
                            if (capabilities.containsKey("se:nodeUri")) {
                                nodeHost = capabilities.get("se:nodeUri").toString();
                            }
                        } catch (Exception e) {
                            System.out.println("⚠️ Could not extract node info: " + e.getMessage());
                        }
                        
                        System.out.println("🎬 Docker Grid video recording started for: " + testName);
                        System.out.println("📹 Session ID: " + sessionId);
                        System.out.println("🖥️ Node Host: " + nodeHost);
                        System.out.println("📁 Video directory: " + VIDEO_DIR);
                    }
                }
            } catch (Exception driverEx) {
                System.out.println("⚠️ Could not get WebDriver session: " + driverEx.getMessage());
            }
            
            // Fallback session generation
            if (sessionId == null) {
                sessionId = generateFallbackSessionId(normalizedTestName);
                System.out.println("🎬 Fallback video recording started for: " + testName);
                System.out.println("📹 Fallback Session ID: " + sessionId);
            }
            
            // Store session information
            VideoSession videoSession = new VideoSession(sessionId, normalizedTestName, 
                                                        System.currentTimeMillis(), nodeHost);
            testSessionMap.put(normalizedTestName, videoSession);
            
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Failed to start video recording: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Enhanced recording stop with multiple retry strategies
     */
    public static synchronized boolean stopRecording(String testName) {
        if (testName == null || testName.trim().isEmpty()) {
            return false;
        }
        
        String normalizedTestName = normalizeTestName(testName);
        
        if (isUtilityTest(normalizedTestName)) {
            return true;
        }
        
        VideoSession videoSession = testSessionMap.get(normalizedTestName);
        
        if (videoSession == null) {
            System.out.println("⚠️ No video session tracked for test: " + normalizedTestName);
            return false;
        }
        
        try {
            System.out.println("🛑 Processing video for test: " + normalizedTestName);
            System.out.println("🆔 Session ID: " + videoSession.sessionId);
            System.out.println("⏱️ Test duration: " + (System.currentTimeMillis() - videoSession.startTime) + "ms");
            
            // Critical: Wait for Docker Grid to complete video generation
            System.out.println("⏳ Waiting for Docker Grid to complete video generation...");
            Thread.sleep(getVideoProcessingDelay());
            
            // Enhanced multi-strategy video retrieval
            boolean videoFound = false;
            int maxRetries = getMaxRetries();
            
            for (int retry = 0; retry < maxRetries && !videoFound; retry++) {
                System.out.println("🔍 Retry " + (retry + 1) + "/" + maxRetries + " - Looking for video files...");
                
                // Strategy 1: Session-based video search
                videoFound = findAndRenameSessionVideo(normalizedTestName, videoSession);
                
                if (!videoFound) {
                    // Strategy 2: Timestamp-based search
                    videoFound = findAndRenameTimestampVideo(normalizedTestName, videoSession);
                }
                
                if (!videoFound) {
                    // Strategy 3: Most recent video search
                    videoFound = findAndRenameMostRecentVideo(normalizedTestName, videoSession);
                }
                
                if (!videoFound) {
                    // Strategy 4: Pattern-based search
                    videoFound = findAndRenamePatternVideo(normalizedTestName, videoSession);
                }
                
                if (!videoFound && retry < maxRetries - 1) {
                    System.out.println("⏳ Waiting " + getRetryDelay() + "ms before next retry...");
                    Thread.sleep(getRetryDelay());
                }
            }
            
            // Cleanup and reporting
            testSessionMap.remove(normalizedTestName);
            
            if (!videoFound) {
                System.out.println("⚠️ Video not generated within timeout for: " + normalizedTestName);
                listVideoDirectoryDebug();
                // Create a placeholder or log entry
                createVideoPlaceholder(normalizedTestName);
            } else {
                System.out.println("✅ Video successfully processed for: " + normalizedTestName);
            }
            
            return videoFound;
            
        } catch (Exception e) {
            System.err.println("❌ Error processing video for " + normalizedTestName + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Strategy 1: Find video by session ID with enhanced patterns
     */
    private static boolean findAndRenameSessionVideo(String testName, VideoSession session) {
        try {
            System.out.println("🔍 Strategy 1: Looking for session-based videos in: " + VIDEO_DIR);
            
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                System.out.println("❌ Video directory does not exist: " + VIDEO_DIR);
                return false;
            }
            
            // Enhanced session-based patterns
            String[] sessionPatterns = {
                session.sessionId.toLowerCase(),
                session.sessionId.substring(0, Math.min(8, session.sessionId.length())).toLowerCase(),
                "session-" + session.sessionId.toLowerCase(),
                session.sessionId.toLowerCase() + "-chrome"
            };
            
            for (String pattern : sessionPatterns) {
                File[] sessionVideos = videoDir.listFiles((dir, name) -> {
                    return name.toLowerCase().contains(pattern) && 
                           name.toLowerCase().endsWith(".mp4") &&
                           !isTestVideoFile(name);
                });
                
                if (sessionVideos != null && sessionVideos.length > 0) {
                    Arrays.sort(sessionVideos, Comparator.comparingLong(File::lastModified).reversed());
                    File sessionVideo = sessionVideos[0];
                    
                    if (isVideoFileValid(sessionVideo)) {
                        return renameVideoToTestName(sessionVideo, testName, "session-id");
                    }
                }
            }
            
            System.out.println("❌ No valid session videos found for patterns: " + Arrays.toString(sessionPatterns));
            return false;
            
        } catch (Exception e) {
            System.err.println("❌ Error in session video search: " + e.getMessage());
            return false;
        }
    }

    /**
     * Strategy 2: Find video by timestamp correlation
     */
    private static boolean findAndRenameTimestampVideo(String testName, VideoSession session) {
        try {
            System.out.println("🔍 Strategy 2: Looking for timestamp-correlated videos");
            
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                return false;
            }
            
            // Look for videos created around the test start time (±5 minutes)
            long testStartTime = session.startTime;
            long timeWindowStart = testStartTime - (5 * 60 * 1000); // 5 minutes before
            long timeWindowEnd = System.currentTimeMillis() + (2 * 60 * 1000); // 2 minutes after now
            
            File[] timestampVideos = videoDir.listFiles((dir, name) -> {
                if (!name.toLowerCase().endsWith(".mp4") || isTestVideoFile(name)) {
                    return false;
                }
                
                File file = new File(dir, name);
                long fileTime = file.lastModified();
                return fileTime >= timeWindowStart && fileTime <= timeWindowEnd && file.length() > 50000;
            });
            
            if (timestampVideos != null && timestampVideos.length > 0) {
                // Sort by how close the timestamp is to the test start time
                Arrays.sort(timestampVideos, (f1, f2) -> {
                    long diff1 = Math.abs(f1.lastModified() - testStartTime);
                    long diff2 = Math.abs(f2.lastModified() - testStartTime);
                    return Long.compare(diff1, diff2);
                });
                
                File timestampVideo = timestampVideos[0];
                System.out.println("📅 Found timestamp-correlated video: " + timestampVideo.getName() + 
                                 " (time diff: " + Math.abs(timestampVideo.lastModified() - testStartTime) / 1000 + "s)");
                
                if (isVideoFileValid(timestampVideo)) {
                    return renameVideoToTestName(timestampVideo, testName, "timestamp");
                }
            }
            
            return false;
            
        } catch (Exception e) {
            System.err.println("❌ Error in timestamp video search: " + e.getMessage());
            return false;
        }
    }

    /**
     * Strategy 3: Enhanced most recent video search
     */
    private static boolean findAndRenameMostRecentVideo(String testName, VideoSession session) {
        try {
            System.out.println("🔍 Strategy 3: Looking for most recent videos");
            
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                return false;
            }
            
            // Find videos created in the last 10 minutes
            long tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000);
            
            File[] recentVideos = videoDir.listFiles((dir, name) -> {
                if (!name.toLowerCase().endsWith(".mp4") || isTestVideoFile(name)) {
                    return false;
                }
                
                File file = new File(dir, name);
                return file.lastModified() >= tenMinutesAgo && file.length() > 100000;
            });
            
            if (recentVideos != null && recentVideos.length > 0) {
                Arrays.sort(recentVideos, Comparator.comparingLong(File::lastModified).reversed());
                
                System.out.println("📹 Found " + recentVideos.length + " recent video(s):");
                for (int i = 0; i < Math.min(3, recentVideos.length); i++) {
                    File video = recentVideos[i];
                    long ageSeconds = (System.currentTimeMillis() - video.lastModified()) / 1000;
                    System.out.println("   " + (i + 1) + ". " + video.getName() + 
                                     " (" + formatFileSize(video.length()) + ", " + ageSeconds + "s old)");
                }
                
                File mostRecentVideo = recentVideos[0];
                if (isVideoFileValid(mostRecentVideo)) {
                    return renameVideoToTestName(mostRecentVideo, testName, "most-recent");
                }
            }
            
            return false;
            
        } catch (Exception e) {
            System.err.println("❌ Error in recent video search: " + e.getMessage());
            return false;
        }
    }

    /**
     * Strategy 4: Pattern-based video search with common naming conventions
     */
    private static boolean findAndRenamePatternVideo(String testName, VideoSession session) {
        try {
            System.out.println("🔍 Strategy 4: Looking for pattern-based videos");
            
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                return false;
            }
            
            // Common Docker Grid video naming patterns
            String[] patterns = {
                "video-", "test-", "session-", "recording-", "chrome-", "selenium-",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm"))
            };
            
            for (String pattern : patterns) {
                File[] patternVideos = videoDir.listFiles((dir, name) -> {
                    return name.toLowerCase().contains(pattern.toLowerCase()) && 
                           name.toLowerCase().endsWith(".mp4") &&
                           !isTestVideoFile(name);
                });
                
                if (patternVideos != null && patternVideos.length > 0) {
                    Arrays.sort(patternVideos, Comparator.comparingLong(File::lastModified).reversed());
                    
                    for (File patternVideo : patternVideos) {
                        if (isVideoFileValid(patternVideo) && 
                            Math.abs(patternVideo.lastModified() - session.startTime) < (15 * 60 * 1000)) {
                            
                            System.out.println("🎯 Found pattern video: " + patternVideo.getName() + 
                                             " (pattern: " + pattern + ")");
                            return renameVideoToTestName(patternVideo, testName, "pattern-" + pattern);
                        }
                    }
                }
            }
            
            return false;
            
        } catch (Exception e) {
            System.err.println("❌ Error in pattern video search: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enhanced video file validation
     */
    private static boolean isVideoFileValid(File videoFile) {
        if (!videoFile.exists() || !videoFile.isFile()) {
            return false;
        }
        
        // Check file size (minimum 50KB for a valid video)
        if (videoFile.length() < 50000) {
            System.out.println("⚠️ Video file too small: " + videoFile.getName() + 
                             " (" + formatFileSize(videoFile.length()) + ")");
            return false;
        }
        
        // Check if file is still being written to
        long initialSize = videoFile.length();
        try {
            Thread.sleep(1000);
            long finalSize = videoFile.length();
            if (initialSize != finalSize) {
                System.out.println("⚠️ Video file still being written: " + videoFile.getName());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        
        return true;
    }

    /**
     * Enhanced video renaming with better error handling
     */
    private static boolean renameVideoToTestName(File sourceVideo, String testName, String strategy) {
        try {
            String targetFileName = testName + ".mp4";
            File targetFile = new File(sourceVideo.getParent(), targetFileName);
            
            // Delete existing target if it exists
            if (targetFile.exists()) {
                if (!targetFile.delete()) {
                    System.out.println("⚠️ Could not delete existing video: " + targetFileName);
                }
            }
            
            // Wait for file to be completely written and stable
            if (!waitForFileStabilization(sourceVideo, 15)) {
                System.out.println("⚠️ Source video file may not be complete: " + sourceVideo.getName());
            }
            
            // Use copy instead of move for better reliability with Docker volumes
            Files.copy(sourceVideo.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            // Verify the copy
            if (targetFile.exists() && targetFile.length() > 50000) {
                System.out.println("✅ Video saved (" + strategy + "): " + sourceVideo.getName() + 
                                 " → " + targetFileName);
                System.out.println("📊 Video size: " + formatFileSize(targetFile.length()));
                
                // Clean up source file
                try {
                    if (sourceVideo.delete()) {
                        System.out.println("🗑️ Cleaned up source: " + sourceVideo.getName());
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ Could not delete source: " + e.getMessage());
                }
                
                return true;
            } else {
                System.out.println("❌ Video copy verification failed for: " + targetFileName);
                // Try to clean up failed copy
                if (targetFile.exists()) {
                    targetFile.delete();
                }
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error renaming video: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Enhanced file stabilization wait with configurable timeout
     */
    private static boolean waitForFileStabilization(File file, int maxWaitSeconds) {
        try {
            long lastSize = -1;
            long lastModified = -1;
            int stableChecks = 0;
            int requiredStableChecks = 3;
            
            for (int i = 0; i < maxWaitSeconds; i++) {
                if (!file.exists()) {
                    return false;
                }
                
                long currentSize = file.length();
                long currentModified = file.lastModified();
                
                if (currentSize == lastSize && currentModified == lastModified && currentSize > 50000) {
                    stableChecks++;
                    if (stableChecks >= requiredStableChecks) {
                        System.out.println("✅ File stabilized: " + file.getName() + 
                                         " (" + formatFileSize(currentSize) + ")");
                        return true;
                    }
                } else {
                    stableChecks = 0;
                }
                
                lastSize = currentSize;
                lastModified = currentModified;
                Thread.sleep(1000);
            }
            
            return file.length() > 50000; // Accept if reasonable size even if not fully stable
            
        } catch (Exception e) {
            System.err.println("❌ Error waiting for file stabilization: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create a placeholder for missing videos
     */
    private static void createVideoPlaceholder(String testName) {
        try {
            File placeholderFile = new File(VIDEO_DIR, testName + "_MISSING_VIDEO.txt");
            String content = "Video recording was expected but not found for test: " + testName + "\n" +
                           "Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n" +
                           "Test execution completed but video file was not generated or found.\n";
            
            Files.write(placeholderFile.toPath(), content.getBytes());
            System.out.println("📝 Created video placeholder: " + placeholderFile.getName());
            
        } catch (Exception e) {
            System.err.println("❌ Could not create video placeholder: " + e.getMessage());
        }
    }

    /**
     * Enhanced directory listing for debugging with better categorization
     */
    private static void listVideoDirectoryDebug() {
        try {
            File videoDir = new File(VIDEO_DIR);
            System.out.println("\n🔍 DETAILED VIDEO DIRECTORY SCAN: " + VIDEO_DIR);
            System.out.println("📁 Directory exists: " + videoDir.exists());
            System.out.println("📁 Directory readable: " + videoDir.canRead());
            System.out.println("📁 Directory writable: " + videoDir.canWrite());
            
            if (!videoDir.exists()) {
                System.out.println("❌ Video directory does not exist");
                checkDockerVolumeMount();
                return;
            }
            
            File[] allFiles = videoDir.listFiles();
            if (allFiles == null || allFiles.length == 0) {
                System.out.println("📁 Video directory is empty");
                checkDockerVolumeMount();
            } else {
                categorizeAndListFiles(allFiles);
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error scanning video directory: " + e.getMessage());
        }
    }

    /**
     * Categorize and list files for better debugging
     */
    private static void categorizeAndListFiles(File[] files) {
        System.out.println("📁 Video directory contents (" + files.length + " files):");
        
        long now = System.currentTimeMillis();
        int videoCount = 0, testVideoCount = 0, recentCount = 0, oldCount = 0;
        
        for (File file : files) {
            String type = file.isDirectory() ? "DIR" : "FILE";
            long ageMinutes = (now - file.lastModified()) / (1000 * 60);
            String category = "";
            
            if (file.isFile()) {
                if (file.getName().toLowerCase().endsWith(".mp4")) {
                    videoCount++;
                    if (isTestVideoFile(file.getName())) {
                        testVideoCount++;
                        category = "[TEST VIDEO]";
                    } else {
                        category = "[RAW VIDEO]";
                    }
                }
                
                if (ageMinutes < 15) {
                    recentCount++;
                    category += "[RECENT]";
                } else if (ageMinutes > 60) {
                    oldCount++;
                    category += "[OLD]";
                }
            }
            
            System.out.println("   [" + type + "] " + category + " " + file.getName() + 
                             " (" + formatFileSize(file.length()) + 
                             ", " + ageMinutes + " min old)");
        }
        
        System.out.println("\n📊 Summary:");
        System.out.println("   - Total files: " + files.length);
        System.out.println("   - Video files: " + videoCount);
        System.out.println("   - Test videos: " + testVideoCount);
        System.out.println("   - Recent files (<15min): " + recentCount);
        System.out.println("   - Old files (>60min): " + oldCount);
    }

    /**
     * Enhanced Docker volume mount checking
     */
    private static void checkDockerVolumeMount() {
        try {
            System.out.println("\n🐳 DOCKER VOLUME MOUNT DIAGNOSIS:");
            
            // Check if we can write to the directory
            File testFile = new File(VIDEO_DIR, "test_write_" + System.currentTimeMillis() + ".tmp");
            
            try {
                if (testFile.createNewFile()) {
                    System.out.println("✅ Directory is writable");
                    testFile.delete();
                } else {
                    System.out.println("❌ Directory is not writable");
                }
            } catch (Exception e) {
                System.out.println("❌ Cannot write to directory: " + e.getMessage());
            }
            
            // Check Docker container information
            checkDockerContainerInfo();
            
            // Check mount points
            checkMountPoints();
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not perform Docker diagnosis: " + e.getMessage());
        }
    }

    private static void checkDockerContainerInfo() {
        try {
            System.out.println("\n🐳 Docker Container Information:");
            
            // Check for Chrome containers
            ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter", "name=chrome", 
                                                 "--format", "table {{.Names}}\\t{{.Status}}\\t{{.Ports}}");
            Process process = pb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("   " + line);
                }
            }
            
            // Check for Selenium Hub
            pb = new ProcessBuilder("docker", "ps", "--filter", "name=selenium-hub", 
                                  "--format", "table {{.Names}}\\t{{.Status}}\\t{{.Ports}}");
            process = pb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("   " + line);
                }
            }
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not check Docker containers: " + e.getMessage());
        }
    }

    private static void checkMountPoints() {
        try {
            System.out.println("\n💾 Mount Point Information:");
            
            // Check if running in Docker
            File cgroupFile = new File("/proc/1/cgroup");
            if (cgroupFile.exists()) {
                String content = new String(Files.readAllBytes(cgroupFile.toPath()));
                if (content.contains("docker")) {
                    System.out.println("🐳 Running inside Docker container");
                } else {
                    System.out.println("💻 Running on host system");
                }
            }
            
            // Check mount information
            ProcessBuilder pb = new ProcessBuilder("mount");
            Process process = pb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("videos") || line.contains(VIDEO_DIR)) {
                        System.out.println("   📁 " + line);
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not check mount points: " + e.getMessage());
        }
    }

    // ==================== CONFIGURATION METHODS ====================

    private static int getVideoProcessingDelay() {
        String delayProperty = System.getProperty("video.processing.delay");
        if (delayProperty != null) {
            try {
                return Integer.parseInt(delayProperty);
            } catch (NumberFormatException e) {
                System.out.println("⚠️ Invalid video processing delay, using default");
            }
        }
        
        // Jenkins environment typically needs more time
        if (System.getenv("JENKINS_URL") != null) {
            return 20000; // 20 seconds for Jenkins
        }
        
        return 15000; // 15 seconds default
    }

    private static int getMaxRetries() {
        String retriesProperty = System.getProperty("video.max.retries");
        if (retriesProperty != null) {
            try {
                return Integer.parseInt(retriesProperty);
            } catch (NumberFormatException e) {
                System.out.println("⚠️ Invalid max retries value, using default");
            }
        }
        
        // More retries for Jenkins environment
        if (System.getenv("JENKINS_URL") != null) {
            return 12; // 12 retries for Jenkins
        }
        
        return 8; // 8 retries default
    }

    private static int getRetryDelay() {
        String delayProperty = System.getProperty("video.retry.delay");
        if (delayProperty != null) {
            try {
                return Integer.parseInt(delayProperty);
            } catch (NumberFormatException e) {
                System.out.println("⚠️ Invalid retry delay value, using default");
            }
        }
        
        return 5000; // 5 seconds default
    }

    // ==================== UTILITY METHODS ====================

    private static String generateFallbackSessionId(String testName) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String threadId = String.valueOf(Thread.currentThread().getId());
        return "fallback_" + testName + "_" + timestamp + "_" + threadId;
    }

    private static boolean isTestVideoFile(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.matches(".*test.*\\.mp4$") || 
               lowerName.matches(".*scenario.*\\.mp4$") ||
               lowerName.matches(".*_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.mp4$");
    }

    private static boolean isUtilityTest(String testName) {
        String lowerName = testName.toLowerCase();
        return lowerName.contains("setup") || 
               lowerName.contains("teardown") ||
               lowerName.contains("test_setup") ||
               lowerName.contains("beforeclass") ||
               lowerName.contains("afterclass");
    }

    private static String normalizeTestName(String testName) {
        return testName.trim()
                .toLowerCase()
                .replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    // ==================== INITIALIZATION AND CLEANUP ====================

    private static void initializeEnvironment() {
        try {
            System.out.println("🎬 Enhanced Video Recording System Initialized");
            System.out.println("📁 Video directory: " + VIDEO_DIR);
            System.out.println("🔧 Configuration:");
            System.out.println("   - Processing delay: " + getVideoProcessingDelay() + "ms");
            System.out.println("   - Max retries: " + getMaxRetries());
            System.out.println("   - Retry delay: " + getRetryDelay() + "ms");
            
            // Environment detection
            detectEnvironment();
            
            // Create video directory
            Path videoPath = Paths.get(VIDEO_DIR);
            if (!Files.exists(videoPath)) {
                Files.createDirectories(videoPath);
                System.out.println("📁 Created video directory: " + VIDEO_DIR);
            }
            
            // Set appropriate permissions
            File videoDir = new File(VIDEO_DIR);
            videoDir.setWritable(true, false);
            videoDir.setReadable(true, false);
            videoDir.setExecutable(true, false);
            
            System.out.println("✅ Video directory initialized successfully");
            
        } catch (Exception e) {
            System.err.println("❌ Failed to initialize video recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void detectEnvironment() {
        System.out.println("🔍 Environment Detection:");
        
        // Docker detection
        boolean dockerMode = "true".equals(System.getProperty("docker.mode"));
        System.out.println("   - Docker Mode: " + dockerMode);
        
        // Jenkins detection
        String jenkinsUrl = System.getenv("JENKINS_URL");
        String buildNumber = System.getenv("BUILD_NUMBER");
        if (jenkinsUrl != null || buildNumber != null) {
            System.out.println("   - Jenkins CI/CD: YES");
            System.out.println("   - Build Number: " + buildNumber);
            System.out.println("   - Job Name: " + System.getenv("JOB_NAME"));
            System.out.println("   - Workspace: " + System.getenv("WORKSPACE"));
        } else {
            System.out.println("   - Jenkins CI/CD: NO");
        }
        
        // WSL detection
        String wslDistro = System.getenv("WSL_DISTRO_NAME");
        if (wslDistro != null) {
            System.out.println("   - WSL2 Environment: YES (" + wslDistro + ")");
        } else {
            System.out.println("   - WSL2 Environment: NO");
        }
        
        // Operating System
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        System.out.println("   - OS: " + osName + " " + osVersion);
        
        // Java version
        String javaVersion = System.getProperty("java.version");
        System.out.println("   - Java: " + javaVersion);
        
        // Selenium Hub
        String hubUrl = System.getProperty("selenium.hub.url");
        if (hubUrl != null) {
            System.out.println("   - Selenium Hub: " + hubUrl);
        }
    }

    /**
     * Stop all active recordings (called during shutdown)
     */
    public static void stopAllRecordings() {
        System.out.println("🛑 Stopping all active video recordings...");
        
        String[] activeTests = testSessionMap.keySet().toArray(new String[0]);
        int successCount = 0;
        
        for (String testName : activeTests) {
            try {
                if (stopRecording(testName)) {
                    successCount++;
                }
            } catch (Exception e) {
                System.err.println("❌ Error stopping recording for " + testName + ": " + e.getMessage());
            }
        }
        
        System.out.println("✅ Video recordings processed: " + successCount + "/" + activeTests.length);
        
        // Generate summary report
        generateVideoSummaryReport();
    }

    /**
     * Generate a summary report of all video recordings
     */
    private static void generateVideoSummaryReport() {
        try {
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                return;
            }
            
            File reportFile = new File(videoDir, "video_summary_" + 
                                     LocalDateTime.now().format(TIMESTAMP_FORMAT) + ".txt");
            
            StringBuilder report = new StringBuilder();
            report.append("VIDEO RECORDING SUMMARY REPORT\n");
            report.append("=====================================\n");
            report.append("Generated: ").append(LocalDateTime.now()).append("\n");
            report.append("Video Directory: ").append(VIDEO_DIR).append("\n\n");
            
            // Environment information
            report.append("ENVIRONMENT:\n");
            report.append("- Docker Mode: ").append(System.getProperty("docker.mode")).append("\n");
            report.append("- Jenkins Build: ").append(System.getenv("BUILD_NUMBER")).append("\n");
            report.append("- OS: ").append(System.getProperty("os.name")).append("\n");
            report.append("- Java: ").append(System.getProperty("java.version")).append("\n\n");
            
            // Video files summary
            File[] videoFiles = videoDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
            if (videoFiles != null && videoFiles.length > 0) {
                report.append("VIDEO FILES (").append(videoFiles.length).append("):\n");
                
                long totalSize = 0;
                for (File videoFile : videoFiles) {
                    totalSize += videoFile.length();
                    report.append("- ").append(videoFile.getName())
                          .append(" (").append(formatFileSize(videoFile.length())).append(")\n");
                }
                
                report.append("\nTOTAL SIZE: ").append(formatFileSize(totalSize)).append("\n");
            } else {
                report.append("NO VIDEO FILES FOUND\n");
            }
            
            // Missing videos
            File[] placeholders = videoDir.listFiles((dir, name) -> name.contains("MISSING_VIDEO"));
            if (placeholders != null && placeholders.length > 0) {
                report.append("\nMISSING VIDEOS (").append(placeholders.length).append("):\n");
                for (File placeholder : placeholders) {
                    report.append("- ").append(placeholder.getName()).append("\n");
                }
            }
            
            Files.write(reportFile.toPath(), report.toString().getBytes());
            System.out.println("📊 Video summary report generated: " + reportFile.getName());
            
        } catch (Exception e) {
            System.err.println("❌ Error generating video summary report: " + e.getMessage());
        }
    }

    /**
     * Get current active recording sessions (for debugging)
     */
    public static Map<String, String> getActiveRecordingSessions() {
        Map<String, String> activeSessions = new ConcurrentHashMap<>();
        testSessionMap.forEach((testName, session) -> 
            activeSessions.put(testName, session.sessionId));
        return activeSessions;
    }

    /**
     * Force cleanup of old video files (housekeeping)
     */
    public static void cleanupOldVideos(int daysOld) {
        try {
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                return;
            }
            
            long cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L);
            File[] oldFiles = videoDir.listFiles((dir, name) -> {
                File file = new File(dir, name);
                return file.lastModified() < cutoffTime && 
                       (name.toLowerCase().endsWith(".mp4") || name.contains("MISSING_VIDEO"));
            });
            
            if (oldFiles != null && oldFiles.length > 0) {
                System.out.println("🧹 Cleaning up " + oldFiles.length + " old video files (>" + daysOld + " days)");
                
                int deletedCount = 0;
                long freedSpace = 0;
                
                for (File oldFile : oldFiles) {
                    long fileSize = oldFile.length();
                    if (oldFile.delete()) {
                        deletedCount++;
                        freedSpace += fileSize;
                        System.out.println("   Deleted: " + oldFile.getName());
                    }
                }
                
                System.out.println("✅ Cleanup completed: " + deletedCount + " files deleted, " + 
                                 formatFileSize(freedSpace) + " freed");
            } else {
                System.out.println("✨ No old video files to clean up");
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error during video cleanup: " + e.getMessage());
        }
    }

    /**
     * Validate video recording configuration
     */
    public static boolean validateConfiguration() {
        try {
            System.out.println("🔧 Validating video recording configuration...");
            
            // Check video directory
            File videoDir = new File(VIDEO_DIR);
            if (!videoDir.exists()) {
                System.out.println("❌ Video directory does not exist: " + VIDEO_DIR);
                return false;
            }
            
            if (!videoDir.canWrite()) {
                System.out.println("❌ Video directory is not writable: " + VIDEO_DIR);
                return false;
            }
            
            // Check Docker mode configuration
            boolean dockerMode = "true".equals(System.getProperty("docker.mode"));
            if (dockerMode) {
                String hubUrl = System.getProperty("selenium.hub.url");
                if (hubUrl == null || hubUrl.isEmpty()) {
                    System.out.println("❌ Selenium hub URL not configured for Docker mode");
                    return false;
                }
                
                // Test hub connectivity
                try {
                    java.net.URL url = new java.net.URL(hubUrl + "/status");
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setRequestMethod("GET");
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode != 200) {
                        System.out.println("❌ Selenium hub not accessible: " + hubUrl + " (HTTP " + responseCode + ")");
                        return false;
                    }
                } catch (Exception e) {
                    System.out.println("❌ Cannot connect to Selenium hub: " + e.getMessage());
                    return false;
                }
            }
            
            System.out.println("✅ Video recording configuration validated successfully");
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Error validating configuration: " + e.getMessage());
            return false;
        }
    }
}