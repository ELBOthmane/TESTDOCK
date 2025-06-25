package Helper;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;

import java.time.Duration;
import java.net.URL;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Enhanced DriverHelper for Docker Swarm Grid with Selenium 4.20.0
 * Supports WSL2, local development, and Jenkins CI/CD environments
 */
public class DriverHelper {
    
    private static final String SELENIUM_HUB_DEFAULT = "http://localhost:4444/wd/hub";
    private static final int CONNECTION_TIMEOUT = 30000; // 30 seconds
    private static final int GRID_READINESS_WAIT = 10000; // 10 seconds
    
    /**
     * Main method to create WebDriver instance with enhanced environment detection
     */
    public static WebDriver ChromeDirverInstance() {
        try {
            System.out.println("🚀 Initializing Enhanced WebDriver...");
            
            WebDriver driver;
            
            // Enhanced environment detection
            EnvironmentConfig config = detectEnvironment();
            logEnvironmentConfig(config);
            
            if (config.isDockerMode) {
                driver = createDockerGridDriver(config);
            } else {
                driver = createLocalDriver(config);
            }

            // Configure driver settings
            configureDriverSettings(driver, config);

            System.out.println("✅ WebDriver created successfully");
            logDriverCapabilities(driver);
            
            return driver;
            
        } catch (Exception e) {
            System.err.println("❌ Failed to create WebDriver: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize WebDriver", e);
        }
    }
    
    /**
     * Environment configuration detection
     */
    private static class EnvironmentConfig {
        boolean isDockerMode;
        boolean isJenkinsMode;
        boolean isWSL2Mode;
        boolean isHeadless;
        String hubUrl;
        String targetUrl;
        Duration pageLoadTimeout;
        Duration implicitTimeout;
        Duration scriptTimeout;
        Map<String, Object> customCapabilities;
        
        EnvironmentConfig() {
            this.customCapabilities = new HashMap<>();
        }
    }
    
    /**
     * Enhanced environment detection with better logic
     */
    private static EnvironmentConfig detectEnvironment() {
        EnvironmentConfig config = new EnvironmentConfig();
        
        // Docker mode detection
        config.isDockerMode = "true".equals(System.getProperty("docker.mode", "false"));
        
        // Jenkins detection
        config.isJenkinsMode = System.getenv("JENKINS_URL") != null || 
                              System.getenv("BUILD_NUMBER") != null;
        
        // WSL2 detection
        config.isWSL2Mode = System.getenv("WSL_DISTRO_NAME") != null;
        
        // Headless mode
        config.isHeadless = "true".equals(System.getProperty("headless", "false"));
        
        // Hub URL configuration
        String hubHost = System.getProperty("selenium.hub.host", "localhost");
        config.hubUrl = System.getProperty("selenium.hub.url", 
                                          "http://" + hubHost + ":4444/wd/hub");
        
        // Target URL
        config.targetUrl = System.getProperty("target.url", "https://www.spoticar.fr/");
        
        // Configure timeouts based on environment
        if (config.isJenkinsMode) {
            // Jenkins needs longer timeouts
            config.pageLoadTimeout = Duration.ofSeconds(getTimeoutProperty("webdriver.timeouts.pageload", 180));
            config.implicitTimeout = Duration.ofSeconds(getTimeoutProperty("webdriver.timeouts.implicit", 20));
            config.scriptTimeout = Duration.ofSeconds(getTimeoutProperty("webdriver.timeouts.script", 120));
        } else {
            // Local/WSL2 timeouts
            config.pageLoadTimeout = Duration.ofSeconds(getTimeoutProperty("webdriver.timeouts.pageload", 60));
            config.implicitTimeout = Duration.ofSeconds(getTimeoutProperty("webdriver.timeouts.implicit", 10));
            config.scriptTimeout = Duration.ofSeconds(getTimeoutProperty("webdriver.timeouts.script", 45));
        }
        
        return config;
    }
    
    private static int getTimeoutProperty(String propertyName, int defaultValue) {
        String property = System.getProperty(propertyName);
        if (property != null) {
            try {
                // Handle both seconds and milliseconds
                int value = Integer.parseInt(property);
                return value > 1000 ? value / 1000 : value; // Convert ms to seconds if needed
            } catch (NumberFormatException e) {
                System.out.println("⚠️ Invalid timeout property " + propertyName + ": " + property);
            }
        }
        return defaultValue;
    }
    
    /**
     * Create RemoteWebDriver for Docker Grid with enhanced reliability
     */
    private static WebDriver createDockerGridDriver(EnvironmentConfig config) {
        try {
            System.out.println("🐳 Creating Docker Grid RemoteWebDriver...");
            
            // Verify Grid availability with retries
            if (!waitForGridAvailability(config.hubUrl, 60)) {
                throw new RuntimeException("Docker Grid is not available at: " + config.hubUrl);
            }
            
            ChromeOptions options = createEnhancedChromeOptions(config);
            
            // Enhanced capabilities for Docker Grid
            DesiredCapabilities capabilities = new DesiredCapabilities();
            capabilities.setBrowserName("chrome");
            capabilities.setCapability(ChromeOptions.CAPABILITY, options);
            
            // Docker Grid specific capabilities
            capabilities.setCapability("se:recordVideo", true);
            capabilities.setCapability("se:videoUploadOnPassing", true);
            capabilities.setCapability("se:videoUploadOnFailing", true);
            capabilities.setCapability("se:screenResolution", "1920x1080x24");
            capabilities.setCapability("se:timeZone", "UTC");
            
            // Jenkins specific capabilities
            if (config.isJenkinsMode) {
                capabilities.setCapability("build", System.getenv("BUILD_NUMBER"));
                capabilities.setCapability("name", System.getenv("JOB_NAME"));
            }
            
            // Custom capabilities
            config.customCapabilities.forEach(capabilities::setCapability);
            
            // Create RemoteWebDriver with retry logic
            RemoteWebDriver driver = createRemoteDriverWithRetry(config.hubUrl, capabilities, 3);
            
            System.out.println("✅ Docker Grid RemoteWebDriver created successfully");
            System.out.println("📹 Session ID: " + driver.getSessionId());
            
            return driver;
            
        } catch (Exception e) {
            System.err.println("❌ Failed to create Docker Grid driver: " + e.getMessage());
            throw new RuntimeException("Failed to create Docker Grid driver", e);
        }
    }
    
    /**
     * Create RemoteWebDriver with retry logic
     */
    private static RemoteWebDriver createRemoteDriverWithRetry(String hubUrl, DesiredCapabilities capabilities, int maxRetries) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                System.out.println("🔄 Creating RemoteWebDriver (attempt " + attempt + "/" + maxRetries + ")");
                
                RemoteWebDriver driver = new RemoteWebDriver(new URL(hubUrl), capabilities);
                
                // Verify driver is responsive
                String sessionId = driver.getSessionId().toString();
                if (sessionId != null && !sessionId.isEmpty()) {
                    System.out.println("✅ RemoteWebDriver created with session: " + sessionId);
                    return driver;
                }
                
            } catch (Exception e) {
                lastException = e;
                System.out.println("❌ Attempt " + attempt + " failed: " + e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        System.out.println("⏳ Waiting 5 seconds before retry...");
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying", ie);
                    }
                }
            }
        }
        
        throw new RuntimeException("Failed to create RemoteWebDriver after " + maxRetries + " attempts", lastException);
    }
    
    /**
     * Create local ChromeDriver with enhanced configuration
     */
    private static WebDriver createLocalDriver(EnvironmentConfig config) {
        try {
            System.out.println("💻 Creating local Chrome driver...");
            
            // Setup WebDriverManager for automatic driver management
            setupWebDriverManager();
            
            // Clean up existing processes
            cleanupChromeProcesses();
            
            ChromeOptions options = createEnhancedChromeOptions(config);
            
            // Create unique user data directory
            String userDataDir = createUniqueUserDataDir();
            options.addArguments("--user-data-dir=" + userDataDir);
            
            // Additional local-specific options
            options.addArguments("--disable-background-networking");
            options.addArguments("--disable-background-timer-throttling");
            options.addArguments("--disable-renderer-backgrounding");
            options.addArguments("--disable-backgrounding-occluded-windows");
            options.addArguments("--disable-client-side-phishing-detection");
            options.addArguments("--disable-crash-reporter");
            options.addArguments("--disable-oopr-debug-crash-dump");
            options.addArguments("--no-crash-upload");
            options.addArguments("--disable-gpu-sandbox");
            options.addArguments("--disable-software-rasterizer");
            
            ChromeDriver driver = new ChromeDriver(options);
            System.out.println("✅ Local Chrome driver created successfully");
            System.out.println("📁 User data dir: " + userDataDir);
            
            return driver;
            
        } catch (Exception e) {
            System.err.println("❌ Failed to create local Chrome driver: " + e.getMessage());
            throw new RuntimeException("Could not create local Chrome driver", e);
        }
    }
    
    /**
     * Setup WebDriverManager for automatic driver management
     */
    private static void setupWebDriverManager() {
        try {
            System.out.println("🔧 Setting up WebDriverManager...");
            
            WebDriverManager.chromedriver()
                .clearDriverCache()
                .clearResolutionCache()
                .setup();
                
            System.out.println("✅ WebDriverManager setup completed");
            
        } catch (Exception e) {
            System.out.println("⚠️ WebDriverManager setup failed: " + e.getMessage());
            // Continue without WebDriverManager - fallback to system driver
        }
    }
    
    /**
     * Create enhanced Chrome options with comprehensive configuration
     */
    private static ChromeOptions createEnhancedChromeOptions(EnvironmentConfig config) {
        ChromeOptions options = new ChromeOptions();
        
        // Essential Chrome arguments
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-web-security");
        options.addArguments("--disable-features=VizDisplayCompositor");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--start-maximized");
        
        // Stability improvements
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        options.addArguments("--disable-default-apps");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-translate");
        options.addArguments("--disable-ipc-flooding-protection");
        
        // Performance optimizations
        options.addArguments("--max_old_space_size=4096");
        options.addArguments("--disable-background-timer-throttling");
        options.addArguments("--disable-renderer-backgrounding");
        options.addArguments("--disable-backgrounding-occluded-windows");
        options.addArguments("--disable-component-extensions-with-background-pages");
        
        // Handle headless mode
        if (config.isHeadless) {
            System.out.println("🔇 Running in headless mode");
            options.addArguments("--headless=new");
            options.addArguments("--disable-gpu");
            options.addArguments("--virtual-time-budget=5000");
        } else {
            System.out.println("🖥️ Running in visual mode");
        }
        
        // Docker-specific optimizations
        if (config.isDockerMode) {
            options.addArguments("--disable-background-media-strategy");
            options.addArguments("--disable-features=TranslateUI");
            options.addArguments("--disable-ipc-flooding-protection");
            options.addArguments("--memory-pressure-off");
            options.addArguments("--max_old_space_size=2048");
        }
        
        // WSL2-specific optimizations
        if (config.isWSL2Mode) {
            options.addArguments("--disable-gpu");
            options.addArguments("--disable-software-rasterizer");
        }
        
        // Jenkins-specific optimizations
        if (config.isJenkinsMode) {
            options.addArguments("--disable-logging");
            options.addArguments("--silent");
            options.addArguments("--disable-gpu-sandbox");
        }
        
        // Performance settings
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        
        // Prefs for better performance
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_settings.popups", 0);
        prefs.put("profile.managed_default_content_settings.images", 2); // Block images for faster loading
        prefs.put("profile.default_content_setting_values.geolocation", 2);
        prefs.put("profile.default_content_setting_values.media_stream", 2);
        options.setExperimentalOption("prefs", prefs);
        
        // Logging preferences
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.BROWSER, Level.SEVERE);
        logPrefs.enable(LogType.DRIVER, Level.WARNING);
        options.setCapability("goog:loggingPrefs", logPrefs);
        
        return options;
    }
    
    /**
     * Wait for Grid availability with better error handling
     */
    private static boolean waitForGridAvailability(String hubUrl, int timeoutSeconds) {
        System.out.println("🔍 Checking Docker Grid availability: " + hubUrl);
        
        for (int i = 0; i < timeoutSeconds; i += 5) {
            try {
                // Extract host and port from URL
                URL url = new URL(hubUrl);
                String host = url.getHost();
                int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
                
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 5000);
                    
                    // Additional check: try to access the status endpoint
                    try {
                        URL statusUrl = new URL(hubUrl + "/status");
                        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) statusUrl.openConnection();
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);
                        connection.setRequestMethod("GET");
                        
                        int responseCode = connection.getResponseCode();
                        if (responseCode == 200) {
                            System.out.println("✅ Docker Grid is ready and responsive");
                            Thread.sleep(GRID_READINESS_WAIT); // Additional wait for full readiness
                            return true;
                        } else {
                            System.out.println("⚠️ Grid responded with HTTP " + responseCode);
                        }
                    } catch (Exception statusEx) {
                        System.out.println("⚠️ Grid status check failed: " + statusEx.getMessage());
                    }
                }
                
            } catch (Exception e) {
                if (i == 0) {
                    System.out.println("⏳ Waiting for Docker Grid to become available...");
                }
                
                if (i % 15 == 0 && i > 0) {
                    System.out.println("⏳ Still waiting... (" + i + "s elapsed)");
                }
                
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        
        System.out.println("❌ Docker Grid not available after " + timeoutSeconds + " seconds");
        return false;
    }
    
    /**
     * Configure driver timeouts and window settings
     */
    private static void configureDriverSettings(WebDriver driver, EnvironmentConfig config) {
        try {
            System.out.println("⚙️ Configuring driver settings...");
            
            // Configure timeouts
            driver.manage().timeouts().implicitlyWait(config.implicitTimeout);
            driver.manage().timeouts().pageLoadTimeout(config.pageLoadTimeout);
            driver.manage().timeouts().scriptTimeout(config.scriptTimeout);
            
            // Window management
            driver.manage().window().maximize();
            
            // Force desktop viewport for consistency
            if (driver instanceof JavascriptExecutor) {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript("window.resizeTo(1920, 1080);");
            }
            
            System.out.println("✅ Driver settings configured successfully");
            System.out.println("   - Implicit wait: " + config.implicitTimeout.getSeconds() + "s");
            System.out.println("   - Page load timeout: " + config.pageLoadTimeout.getSeconds() + "s");
            System.out.println("   - Script timeout: " + config.scriptTimeout.getSeconds() + "s");
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not configure all driver settings: " + e.getMessage());
        }
    }
    
    /**
     * Log environment configuration for debugging
     */
    private static void logEnvironmentConfig(EnvironmentConfig config) {
        System.out.println("\n🔧 Environment Configuration:");
        System.out.println("   - Docker Mode: " + config.isDockerMode);
        System.out.println("   - Jenkins Mode: " + config.isJenkinsMode);
        System.out.println("   - WSL2 Mode: " + config.isWSL2Mode);
        System.out.println("   - Headless: " + config.isHeadless);
        System.out.println("   - Hub URL: " + config.hubUrl);
        System.out.println("   - Target URL: " + config.targetUrl);
        System.out.println("   - Page Load Timeout: " + config.pageLoadTimeout.getSeconds() + "s");
        System.out.println("   - Implicit Timeout: " + config.implicitTimeout.getSeconds() + "s");
        System.out.println("   - Script Timeout: " + config.scriptTimeout.getSeconds() + "s");
        
        if (config.isJenkinsMode) {
            System.out.println("   - Build Number: " + System.getenv("BUILD_NUMBER"));
            System.out.println("   - Job Name: " + System.getenv("JOB_NAME"));
            System.out.println("   - Workspace: " + System.getenv("WORKSPACE"));
        }
        
        System.out.println();
    }
    
    /**
     * Log driver capabilities for debugging
     */
    private static void logDriverCapabilities(WebDriver driver) {
        try {
            if (driver instanceof RemoteWebDriver) {
                RemoteWebDriver remoteDriver = (RemoteWebDriver) driver;
                System.out.println("\n📋 Driver Capabilities:");
                System.out.println("   - Browser: " + remoteDriver.getCapabilities().getBrowserName());
                System.out.println("   - Version: " + remoteDriver.getCapabilities().getBrowserVersion());
                System.out.println("   - Platform: " + remoteDriver.getCapabilities().getPlatformName());
                System.out.println("   - Session ID: " + remoteDriver.getSessionId());
                
                // Log additional capabilities
                Map<String, Object> caps = remoteDriver.getCapabilities().asMap();
                if (caps.containsKey("se:nodeUri")) {
                    System.out.println("   - Node URI: " + caps.get("se:nodeUri"));
                }
                if (caps.containsKey("se:recordVideo")) {
                    System.out.println("   - Video Recording: " + caps.get("se:recordVideo"));
                }
            }
            System.out.println();
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not log driver capabilities: " + e.getMessage());
        }
    }
    
    /**
     * Create unique user data directory for local Chrome instances
     */
    private static String createUniqueUserDataDir() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String threadId = String.valueOf(Thread.currentThread().getId());
        String processId = String.valueOf(ProcessHandle.current().pid());
        
        String userDataDir = System.getProperty("java.io.tmpdir") + 
                           File.separator + "chrome-user-data-" + timestamp + "-" + threadId + "-" + processId;
        
        File userDataDirFile = new File(userDataDir);
        if (userDataDirFile.exists()) {
            System.out.println("🧹 Cleaning existing user data dir: " + userDataDir);
            deleteDirectory(userDataDirFile);
        }
        
        return userDataDir;
    }
    
    /**
     * Alternative method name for compatibility
     */
    public static WebDriver createWebDriver() {
        return ChromeDirverInstance();
    }
    
    /**
     * Clean up Chrome resources and processes
     */
    public static void cleanupChromeResources() {
        try {
            System.out.println("🧹 Cleaning up Chrome resources...");
            
            // Clean up Chrome processes
            cleanupChromeProcesses();
            
            // Clean up temp chrome directories
            cleanupTempDirectories();
            
            System.out.println("✅ Chrome resources cleaned up successfully");
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not clean up all Chrome resources: " + e.getMessage());
        }
    }
    
    /**
     * Clean up Chrome processes with enhanced cross-platform support
     */
    private static void cleanupChromeProcesses() {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            
            if (osName.contains("windows")) {
                // Windows cleanup
                cleanupWindowsChromeProcesses();
            } else {
                // Unix/Linux/WSL cleanup
                cleanupUnixChromeProcesses();
            }
            
            // Wait for cleanup to complete
            Thread.sleep(2000);
            
        } catch (Exception e) {
            System.out.println("⚠️ Chrome process cleanup failed: " + e.getMessage());
        }
    }
    
    private static void cleanupWindowsChromeProcesses() {
        try {
            String[] commands = {
                "taskkill /F /IM chrome.exe /T",
                "taskkill /F /IM chromedriver.exe /T",
                "taskkill /F /IM chromedriver /T"
            };
            
            for (String command : commands) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command);
                    pb.start().waitFor();
                } catch (Exception e) {
                    // Ignore individual command failures
                }
            }
            
        } catch (Exception e) {
            System.out.println("⚠️ Windows Chrome cleanup failed: " + e.getMessage());
        }
    }
    
    private static void cleanupUnixChromeProcesses() {
        try {
            String[] commands = {
                "pkill -f chrome",
                "pkill -f chromedriver",
                "killall chrome",
                "killall chromedriver"
            };
            
            for (String command : commands) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
                    pb.start().waitFor();
                } catch (Exception e) {
                    // Ignore individual command failures
                }
            }
            
        } catch (Exception e) {
            System.out.println("⚠️ Unix Chrome cleanup failed: " + e.getMessage());
        }
    }
    
    /**
     * Clean up temporary directories
     */
    private static void cleanupTempDirectories() {
        try {
            String tmpDir = System.getProperty("java.io.tmpdir");
            File tempDir = new File(tmpDir);
            
            if (tempDir.exists()) {
                File[] chromeDirs = tempDir.listFiles((dir, name) -> 
                    name.startsWith("chrome-user-data-") || 
                    name.startsWith("chrome-fallback-") ||
                    name.startsWith(".com.google.Chrome") ||
                    name.startsWith("scoped_dir"));
                
                if (chromeDirs != null) {
                    int deletedCount = 0;
                    for (File chromeDir : chromeDirs) {
                        if (chromeDir.isDirectory()) {
                            try {
                                deleteDirectory(chromeDir);
                                deletedCount++;
                            } catch (Exception e) {
                                System.out.println("⚠️ Could not delete: " + chromeDir.getName());
                            }
                        }
                    }
                    
                    if (deletedCount > 0) {
                        System.out.println("🗑️ Deleted " + deletedCount + " temporary Chrome directories");
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("⚠️ Temp directory cleanup failed: " + e.getMessage());
        }
    }
    
    /**
     * Helper method to delete directory recursively
     */
    private static void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            file.deleteOnExit(); // Fallback for locked files
                        }
                    }
                }
            }
            if (!directory.delete()) {
                directory.deleteOnExit(); // Fallback for locked directories
            }
        }
    }
    
    /**
     * Validate WebDriver configuration before creation
     */
    public static boolean validateConfiguration() {
        try {
            System.out.println("🔧 Validating WebDriver configuration...");
            
            EnvironmentConfig config = detectEnvironment();
            
            // Validate Docker mode configuration
            if (config.isDockerMode) {
                if (!isValidUrl(config.hubUrl)) {
                    System.out.println("❌ Invalid Selenium Hub URL: " + config.hubUrl);
                    return false;
                }
                
                // Test connectivity
                try {
                    URL url = new URL(config.hubUrl);
                    String host = url.getHost();
                    int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
                    
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(host, port), 5000);
                    }
                    
                } catch (Exception e) {
                    System.out.println("❌ Cannot connect to Selenium Hub: " + e.getMessage());
                    return false;
                }
            } else {
                // Validate local mode - check if Chrome is available
                if (!isChromeAvailable()) {
                    System.out.println("❌ Chrome browser not found for local mode");
                    return false;
                }
            }
            
            // Validate target URL
            if (!isValidUrl(config.targetUrl)) {
                System.out.println("❌ Invalid target URL: " + config.targetUrl);
                return false;
            }
            
            System.out.println("✅ WebDriver configuration validated successfully");
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Error validating configuration: " + e.getMessage());
            return false;
        }
    }
    
    private static boolean isValidUrl(String urlString) {
        try {
            new URL(urlString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean isChromeAvailable() {
        try {
            // Try to find Chrome executable
            String osName = System.getProperty("os.name").toLowerCase();
            String[] chromePaths;
            
            if (osName.contains("windows")) {
                chromePaths = new String[]{
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
                };
            } else if (osName.contains("mac")) {
                chromePaths = new String[]{
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                };
            } else {
                chromePaths = new String[]{
                    "/usr/bin/google-chrome",
                    "/usr/bin/google-chrome-stable",
                    "/usr/bin/chromium-browser",
                    "/snap/bin/chromium"
                };
            }
            
            for (String path : chromePaths) {
                if (new File(path).exists()) {
                    return true;
                }
            }
            
            // Try to execute chrome command
            try {
                ProcessBuilder pb = new ProcessBuilder("google-chrome", "--version");
                pb.start().waitFor();
                return true;
            } catch (Exception e) {
                // Try chromium
                try {
                    ProcessBuilder pb = new ProcessBuilder("chromium-browser", "--version");
                    pb.start().waitFor();
                    return true;
                } catch (Exception e2) {
                    return false;
                }
            }
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get driver health status for monitoring
     */
    public static Map<String, Object> getDriverHealthStatus(WebDriver driver) {
        Map<String, Object> status = new HashMap<>();
        
        try {
            if (driver != null) {
                status.put("alive", true);
                status.put("currentUrl", driver.getCurrentUrl());
                status.put("title", driver.getTitle());
                status.put("windowHandles", driver.getWindowHandles().size());
                
                if (driver instanceof RemoteWebDriver) {
                    RemoteWebDriver remoteDriver = (RemoteWebDriver) driver;
                    status.put("sessionId", remoteDriver.getSessionId().toString());
                    status.put("browserName", remoteDriver.getCapabilities().getBrowserName());
                    status.put("browserVersion", remoteDriver.getCapabilities().getBrowserVersion());
                }
                
                // Test basic functionality
                try {
                    ((JavascriptExecutor) driver).executeScript("return document.readyState;");
                    status.put("jsExecution", true);
                } catch (Exception e) {
                    status.put("jsExecution", false);
                    status.put("jsError", e.getMessage());
                }
                
            } else {
                status.put("alive", false);
                status.put("error", "Driver is null");
            }
            
        } catch (Exception e) {
            status.put("alive", false);
            status.put("error", e.getMessage());
        }
        
        return status;
    }
    
    /**
     * Performance optimization for existing driver instances
     */
    public static void optimizeDriverPerformance(WebDriver driver) {
        try {
            if (driver instanceof JavascriptExecutor) {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                
                // Disable animations for faster execution
                js.executeScript(
                    "var style = document.createElement('style'); " +
                    "style.type = 'text/css'; " +
                    "style.innerHTML = '*, *::before, *::after { " +
                    "  animation-duration: 0.01ms !important; " +
                    "  animation-delay: 0.01ms !important; " +
                    "  transition-duration: 0.01ms !important; " +
                    "  transition-delay: 0.01ms !important; " +
                    "}'; " +
                    "document.head.appendChild(style);"
                );
                
                // Disable scroll behavior
                js.executeScript("document.documentElement.style.scrollBehavior = 'auto';");
                
                System.out.println("⚡ Driver performance optimizations applied");
            }
            
        } catch (Exception e) {
            System.out.println("⚠️ Could not apply performance optimizations: " + e.getMessage());
        }
    }
}