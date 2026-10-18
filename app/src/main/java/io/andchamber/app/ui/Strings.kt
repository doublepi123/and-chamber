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
    const val unsupportedTitle = "This phone can't run the local server"

    fun unsupportedBody(abi: String) =
        "The local server needs a 64-bit ARM (arm64-v8a) or x86_64 processor, and this device reports $abi. " +
            "You can still connect to OpenChamber running on another computer."

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

    const val repairDialogTitle = "Repair the Linux system?"
    const val repairDialogBody =
        "This stops OpenChamber, deletes the Linux system and installs it again, which downloads about 1.2 GB. " +
            "Your projects are kept when possible."
    const val uninstallDialogTitle = "Uninstall the local server?"
    const val uninstallDialogBody =
        "This stops OpenChamber and deletes the Linux system from this phone, including projects, sessions " +
            "and settings stored inside it. Saved servers are not affected."
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
