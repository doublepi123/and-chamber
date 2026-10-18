package io.andchamber.app.ui

import io.andchamber.app.runtime.InstallStep

/** User-facing copy. English only for now; kept in one place so it can move to resources later. */
internal object Strings {
    const val appName = "AndChamber"
    const val tagline = "OpenChamber on this phone, or on your computer"

    // Sections
    const val sectionThisDevice = "On this phone"
    const val sectionServers = "On other computers"

    // Local runtime card
    const val localTitle = "OpenChamber"
    const val localSubtitle = "Local server"
    const val statusChecking = "Checking"
    const val statusNotSetUp = "Not set up"
    const val statusSettingUp = "Setting up"
    const val statusStopped = "Stopped"
    const val statusStarting = "Starting"
    const val statusRunning = "Running"
    const val statusStopping = "Stopping"
    const val statusProblem = "Problem"
    const val statusUnsupported = "Not supported"

    const val setupIntro =
        "Installs a small Linux system on this phone and runs OpenChamber and OpenCode inside it. " +
            "Your projects and sessions stay on the device."
    const val setupFactContents = "Alpine Linux, Node.js, Git, OpenCode and OpenChamber"
    const val setupFactSize = "About 1.2 GB to download and store"
    const val setupFactWifi = "Wi-Fi recommended"
    const val setupFactTime = "Takes a few minutes and keeps going in the background"

    fun setupFactFree(free: String) = "$free free on this phone"

    const val setupLowSpace = "That may not be enough space."
    const val setUp = "Set up"

    fun stepOf(
        current: Int,
        total: Int,
    ) = "Step $current of $total"

    const val setupBackgroundNote =
        "You can leave the app. Setup keeps going in the background and shows its progress in a notification."

    const val stoppedBody = "OpenChamber is installed. Start the server to open it."
    const val start = "Start"
    const val startingTitle = "Starting OpenChamber…"
    const val stoppingTitle = "Stopping OpenChamber…"
    const val open = "Open"
    const val stop = "Stop"

    fun uptime(text: String) = "Up $text"

    fun port(port: Int) = "Port $port"

    const val justStarted = "Just started"
    const val copyAddress = "Copy address"
    const val copied = "Copied"

    const val failedTitle = "OpenChamber ran into a problem"
    const val tryAgain = "Try again"
    const val repair = "Repair"
    const val repairExplain = "Repair reinstalls the Linux system. Your projects are kept when possible."
    const val unsupportedTitle = "Not available on this device"
    const val unsupportedBody =
        "Running OpenChamber on the device needs an ARM64 (arm64-v8a) phone or tablet. " +
            "You can still connect to OpenChamber on your computer below."

    const val showLog = "Log"
    const val details = "Details"
    const val logTitle = "Log"

    fun logLines(count: Int) = if (count == 1) "1 line" else "$count lines"

    const val logEmpty = "No output yet."
    const val copyLog = "Copy log"

    // Details
    const val detailAbi = "Processor"
    const val detailAlpine = "Alpine Linux"
    const val detailNode = "Node.js"
    const val detailOpenCode = "OpenCode"
    const val detailOpenChamber = "OpenChamber"
    const val detailDiskUsed = "Disk used"
    const val detailFreeSpace = "Free space"
    const val unknownValue = "—"
    const val uninstall = "Uninstall"

    // Shared storage
    const val storageTitle = "Projects and settings folder"
    const val storageOff = "Off"
    const val storageNeedsAccess = "Needs access"
    const val storageOn = "On"
    const val storageFolderName = "AndChamber/"
    const val storageIntro =
        "Keep projects and OpenCode settings in AndChamber/ on your phone's storage, so you can edit them " +
            "with a file manager or over USB."
    const val storageNeedsAllFiles =
        "AndChamber needs all-files access to use this folder. You can turn it on in system settings."
    const val storageNeedsLegacy = "AndChamber needs access to your phone's storage to use this folder."
    const val allowAccess = "Allow access"
    const val storageOthersAllFiles = "Other apps with all-files access can read this folder."
    const val storageOthersLegacy = "Other apps with storage access can read this folder."
    const val storageProjectsDir = "projects/"
    const val storageOpencodeDir = "opencode/"
    const val guestWorkspace = "/workspace"
    const val guestPrivateWorkspace = "/workspace-private"
    const val storageOpencodeTarget = "OpenCode settings"
    const val storagePrivate = "Private: sessions, sign-ins, plugins, /workspace-private"
    const val storageUnavailable =
        "AndChamber has access, but can't create AndChamber/ on your phone's storage. " +
            "Make sure the storage is available."
    const val checkAgain = "Check again"
    const val storageCaveat =
        "Shared storage doesn't keep symlinks or executable bits — use /workspace-private for projects " +
            "that need node_modules."
    const val copyFolderPath = "Copy folder path"
    const val restartToApply = "Restart the server to apply"
    const val restart = "Restart"
    const val storageOffDialogTitle = "Stop using the shared folder?"
    const val storageOffDialogBody =
        "Your files stay in AndChamber/ on your phone's storage. From the next server start, OpenChamber " +
            "uses its private folders again and won't see them."
    const val turnOff = "Turn off"
    const val cantOpenSettings = "Couldn't open system settings"

    const val repairDialogTitle = "Repair the Linux system?"
    const val repairDialogBody =
        "This stops OpenChamber, deletes the Linux system and installs it again, which downloads about 1.2 GB. " +
            "Your projects are kept when possible."
    const val uninstallDialogTitle = "Uninstall the local server?"
    const val uninstallDialogBody =
        "This stops OpenChamber and deletes the Linux system from this phone, including projects, sessions " +
            "and settings stored inside it. Saved servers are not affected."
    const val uninstallDialogBodyShared =
        "This stops OpenChamber and deletes the Linux system from this phone, including everything stored " +
            "inside it. The AndChamber/ folder on your phone's storage is kept. Saved servers are not affected."
    const val cancel = "Cancel"

    // Remote servers
    const val addServer = "Add server"
    const val add = "Add"
    const val editServer = "Edit server"
    const val emptyServersTitle = "Connect to your computer"
    const val emptyServersBody =
        "Add the address of an OpenChamber server on your computer or network, for example 192.168.1.20:3000."
    const val edit = "Edit"
    const val remove = "Remove"
    const val moreOptions = "More options"
    const val passwordSaved = "Password saved"

    fun removeServerTitle(name: String) = "Remove $name?"

    const val removeServerBody =
        "The saved address and password are deleted from this phone. The server itself isn't changed."

    // Server dialog
    const val fieldAddress = "Address"
    const val fieldAddressPlaceholder = "192.168.1.20:3000"

    fun addressPreview(url: String) = "Connects to $url"

    const val addressHint = "Host name or IP address, with the port if needed"
    const val fieldName = "Name (optional)"
    const val fieldPassword = "UI password (optional)"
    const val passwordHint = "Only needed if the server asks for a password"
    const val insecureWarning = "Passwords and traffic are sent unencrypted over this network."
    const val allowInsecure = "Allow unencrypted connection"
    const val insecureNeedsConfirm = "Turn on \"Allow unencrypted connection\" to save a password for this address."
    const val showPassword = "Show password"
    const val hidePassword = "Hide password"
    const val save = "Save"
    const val addressRequired = "Enter the server address"
    const val addressNoSpaces = "The address can't contain spaces"
    const val addressScheme = "Use an http:// or https:// address"
    const val addressInvalid = "This doesn't look like a valid address"
    const val addressNoHost = "Add a host name or IP address"

    // Web screen
    fun connectingTo(name: String) = "Connecting to $name"

    const val openingLocal = "Opening OpenChamber"

    fun cantReach(name: String) = "Can't reach $name"

    const val cantReachLocal = "Can't open OpenChamber"
    const val serverFallbackName = "the server"
    const val connectionFailed = "The connection failed"
    const val repairFromHome = "Open the home screen to repair the Linux system."

    const val unreachableHintRemote =
        "Make sure the server is running and this phone can reach it, for example on the same Wi-Fi."
    const val unreachableHintLocal = "The server on this device didn't answer."
    const val back = "Back"
    const val home = "Home"
    const val reload = "Reload"
    const val openInBrowser = "Open in browser"
    const val pageFailedTitle = "The page didn't load"
    const val localStoppedTitle = "OpenChamber stopped"
    const val localStoppedBody = "The server on this device isn't running."
    const val localFailedTitle = "OpenChamber stopped unexpectedly"
    const val startAgain = "Start again"
    const val localNotSetUpTitle = "The local server isn't set up"
    const val localNotSetUpBody = "Set it up from the home screen first."
    const val settingUpTitle = "Setting up…"
    const val serverRemovedTitle = "This server is no longer saved"
    const val menuDescription = "AndChamber menu"
    const val pageCrashed = "The page stopped working and was reloaded"
    const val pageCrashedRepeatedly = "The page keeps stopping. Reload to try again."
    const val noAppForLink = "No app can open this link"
    const val certificateNotTrusted = "The server's certificate isn't trusted"

    fun downloading(name: String) = "Downloading $name"

    fun savedToDownloads(name: String) = "Saved $name to Downloads"

    const val downloadFailed = "Couldn't save the file"
}

internal fun InstallStep.label(): String =
    when (this) {
        InstallStep.DownloadRootfs -> "Download the Linux base"
        InstallStep.ExtractRootfs -> "Unpack the Linux base"
        InstallStep.InstallPackages -> "Install system packages"
        InstallStep.InstallOpenChamber -> "Install OpenChamber and OpenCode"
        InstallStep.BuildNativeModules -> "Build terminal support"
        InstallStep.Verify -> "Check the installation"
    }
