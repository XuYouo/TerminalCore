package com.ai.assistance.operit.terminal.setup

import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.utils.SourceManager

data class EnvironmentSetupPackageDefinition(
    val id: String,
    val command: String,
    val categoryId: String
)

object EnvironmentSetupLogic {
    const val TARGET_NODE_MAJOR = 22

    val packageDefinitions: List<EnvironmentSetupPackageDefinition> = listOf(
        EnvironmentSetupPackageDefinition(
            id = "nodejs",
            command = buildNodejsLtsInstallCommand(),
            categoryId = "nodejs"
        ),
        EnvironmentSetupPackageDefinition(
            id = "pnpm",
            command = "typescript",
            categoryId = "nodejs"
        ),
        EnvironmentSetupPackageDefinition(
            id = "python-is-python3",
            command = "python-is-python3",
            categoryId = "python"
        ),
        EnvironmentSetupPackageDefinition(
            id = "python3-venv",
            command = "python3-venv",
            categoryId = "python"
        ),
        EnvironmentSetupPackageDefinition(
            id = "python3-pip",
            command = "python3-pip",
            categoryId = "python"
        ),
        EnvironmentSetupPackageDefinition(
            id = "uv",
            command = "pipx install uv",
            categoryId = "python"
        ),
        EnvironmentSetupPackageDefinition(id = "ssh", command = "ssh", categoryId = "ssh"),
        EnvironmentSetupPackageDefinition(
            id = "sshpass",
            command = "sshpass",
            categoryId = "ssh"
        ),
        EnvironmentSetupPackageDefinition(
            id = "openssh-server",
            command = "openssh-server",
            categoryId = "ssh"
        ),
        EnvironmentSetupPackageDefinition(
            id = "openjdk-17",
            command = "openjdk-17-jdk",
            categoryId = "java"
        ),
        EnvironmentSetupPackageDefinition(
            id = "gradle",
            command = "gradle",
            categoryId = "java"
        ),
        EnvironmentSetupPackageDefinition(
            id = "rust",
            command = "RUST_INSTALL_COMMAND",
            categoryId = "rust"
        ),
        EnvironmentSetupPackageDefinition(
            id = "go",
            command = "golang-go",
            categoryId = "go"
        ),
        EnvironmentSetupPackageDefinition(
            id = "git",
            command = "git",
            categoryId = "tools"
        ),
        EnvironmentSetupPackageDefinition(
            id = "ffmpeg",
            command = "ffmpeg",
            categoryId = "tools"
        )
    )

    fun buildInstallCommands(
        selectedPackageIds: Collection<String>,
        sourceManager: SourceManager
    ): List<String> {
        val selectedIdSet = selectedPackageIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val selectedPackages = packageDefinitions.filter { selectedIdSet.contains(it.id) }
        if (selectedPackages.isEmpty()) {
            return emptyList()
        }

        val commands = mutableListOf<String>()
        commands += "dpkg --configure -a"
        commands += "apt install -f -y"
        if (selectedIdSet.contains("nodejs")) {
            commands += buildNodejsReinstallCleanupCommand()
        }
        commands += "apt update -y"
        commands += "apt upgrade -y"
        commands += "mkdir -p ~/.config/pip"
        commands += "echo '[global]' > ~/.config/pip/pip.conf"
        commands += "echo 'index-url = https://pypi.tuna.tsinghua.edu.cn/simple' >> ~/.config/pip/pip.conf"
        commands += "mkdir -p ~/.config/uv"
        commands += "echo 'index-url = \"https://pypi.tuna.tsinghua.edu.cn/simple\"' > ~/.config/uv/uv.toml"

        val selectedAptPackages = mutableListOf<String>()
        val selectedNpmPackages = mutableListOf<String>()
        val selectedCustomCommands = mutableListOf<String>()

        selectedPackages.forEach { pkg ->
            when {
                pkg.id == "rust" -> {
                    val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)
                    val rustEnvCommand = sourceManager.getRustSourceEnvCommand(rustSource)
                    selectedCustomCommands +=
                        "$rustEnvCommand && curl -v --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"
                }

                pkg.id == "uv" || pkg.id == "nodejs" -> {
                    selectedCustomCommands += pkg.command
                }

                pkg.categoryId == "nodejs" && pkg.id != "nodejs" -> {
                    selectedNpmPackages += pkg.command
                }

                else -> {
                    selectedAptPackages += pkg.command
                }
            }
        }

        if (selectedIdSet.contains("uv")) {
            selectedAptPackages += "pipx"
        }

        val allAptDependencies = linkedSetOf<String>()
        if (selectedCustomCommands.isNotEmpty()) {
            if (selectedIdSet.contains("rust")) {
                allAptDependencies += listOf("curl", "build-essential")
            }
            if (selectedIdSet.contains("nodejs")) {
                allAptDependencies += listOf("ca-certificates", "curl", "gnupg")
            }
        }
        allAptDependencies += selectedAptPackages

        if (allAptDependencies.isNotEmpty()) {
            commands += "apt install -y ${allAptDependencies.joinToString(" ")}"
        }

        if (selectedCustomCommands.isNotEmpty()) {
            commands += selectedCustomCommands
            if (selectedIdSet.contains("uv")) {
                commands += "pipx ensurepath"
                commands += "source ~/.profile"
            }
        }

        if (selectedNpmPackages.isNotEmpty()) {
            commands += "npm config set registry https://registry.npmmirror.com/"
            commands += "npm cache clean --force"
            commands += "npm install -g pnpm"
            commands += "pnpm add -g ${selectedNpmPackages.joinToString(" ")}"
        }

        return commands
    }

    fun buildCheckCommand(packageId: String, packageCommand: String): String = when (packageId) {
        "rust" -> "command -v rustc"
        "uv" -> "command -v uv"
        "nodejs" -> "node -v 2>/dev/null"
        "pnpm" -> "test -f \"\$(npm prefix -g)/bin/pnpm\" && echo FOUND_PNPM"
        "go" -> "command -v go"
        "git" -> "command -v git"
        "ffmpeg" -> "command -v ffmpeg"
        "ssh" -> "command -v ssh"
        "sshpass" -> "command -v sshpass"
        "openssh-server" -> "command -v sshd"
        "gradle" -> "command -v gradle"
        else -> "dpkg -s ${packageCommand.split(" ").first()}"
    }

    fun isPackageInstalled(packageId: String, output: String): Boolean = when (packageId) {
        "nodejs" -> {
            if (output.isBlank() || output.contains("not found")) {
                false
            } else {
                val versionMatch = Regex("""v(\d+)\..*""").find(output)
                val majorVersion = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                majorVersion == TARGET_NODE_MAJOR
            }
        }

        "rust", "uv", "go", "git", "ffmpeg", "ssh", "sshpass", "openssh-server", "gradle" -> {
            output.isNotBlank() && !output.contains("not found")
        }

        "pnpm" -> output.contains("FOUND_PNPM")
        else -> output.contains("Status: install ok installed")
    }

    private fun buildNodejsLtsInstallCommand(): String = """
        mkdir -p /etc/apt/keyrings
        rm -f /etc/apt/keyrings/nodesource.gpg
        curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
        echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${TARGET_NODE_MAJOR}.x nodistro main" > /etc/apt/sources.list.d/nodesource.list
        apt update -y
        apt install -y nodejs
    """.trimIndent()

    private fun buildNodejsReinstallCleanupCommand(): String = """
        rm -f /etc/apt/sources.list.d/nodesource.list
        rm -f /etc/apt/preferences.d/nodesource
        rm -f /usr/share/keyrings/nodesource.gpg /etc/apt/keyrings/nodesource.gpg
        for pkg in nodejs npm nodejs-doc libnode-dev; do
          if dpkg -s "${'$'}pkg" >/dev/null 2>&1; then
            apt purge -y "${'$'}pkg"
          fi
        done
        rm -f /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/pnpm
        rm -f "${'$'}HOME/.local/bin/node" "${'$'}HOME/.local/bin/npm" "${'$'}HOME/.local/bin/npx" "${'$'}HOME/.local/bin/corepack" "${'$'}HOME/.local/bin/pnpm"
        rm -rf /usr/local/lib/node_modules
        rm -rf "${'$'}HOME/.npm" "${'$'}HOME/.cache/node-gyp"
        apt autoremove -y || true
        hash -r || true
    """.trimIndent()
}
