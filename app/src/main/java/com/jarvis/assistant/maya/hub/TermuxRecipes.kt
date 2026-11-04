package com.jarvis.assistant.maya.hub

/**
 * Named Termux setup scripts ("recipes"), ported from Maya's `termux_setup_recipe`.
 *
 * Maya shipped: hermes, openclaw, ubuntu, hunter-setup (the author's private Turmux-setup
 * script) and dev-basics. `hunter-setup` is replaced by a generic `jarvis-setup` recipe that
 * installs python/git/nodejs/openssh/termux-api. Scripts are defensive (`set -e`, `pkg install -y`)
 * and idempotent so re-running a recipe is safe.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable.
 */
object TermuxRecipes {

    data class Recipe(
        val name: String,
        val title: String,
        val description: String,
        /** Human estimate, e.g. "10-15 min". */
        val estimatedTime: String,
        /** True when the script asks questions and must run in a visible session. */
        val interactive: Boolean,
        val script: String,
        /** One-liner that prints something useful when the recipe succeeded. */
        val verifyCommand: String,
        /** What to tell the user once the recipe has been started/finished. */
        val afterNote: String
    )

    /** Jarvis scratch folder inside Termux (mirrors Maya's ~/.maya). */
    const val JARVIS_HOME_DIR = "\$HOME/.jarvis"
    const val JARVIS_JOBS_DIR = "\$HOME/.jarvis/jobs"
    const val JARVIS_RECIPES_DIR = "\$HOME/.jarvis/recipes"

    private val D = "$"

    private val HEADER = """#!/data/data/com.termux/files/usr/bin/bash
set -e
export DEBIAN_FRONTEND=noninteractive
mkdir -p "${D}HOME/.jarvis" "${D}HOME/.jarvis/jobs" "${D}HOME/.jarvis/recipes"
pkg update -y >/dev/null 2>&1 || true
"""

    val all: Map<String, Recipe> = linkedMapOf(
        "jarvis-setup" to Recipe(
            name = "jarvis-setup",
            title = "Jarvis dev basics",
            description = "Python, git, Node.js, OpenSSH, Termux:API helpers, curl/wget, ripgrep, jq and tmux - the baseline every other recipe expects.",
            estimatedTime = "5-10 min",
            interactive = false,
            script = HEADER + """
pkg install -y python git nodejs openssh termux-api curl wget ripgrep tmux jq
termux-setup-storage </dev/null >/dev/null 2>&1 || true
python -m pip install --upgrade pip >/dev/null 2>&1 || true
echo "jarvis-setup: done"
python --version; node --version; git --version
""",
            verifyCommand = "command -v python && command -v node && command -v git && command -v ssh && echo ok",
            afterNote = "Python, git, Node.js and OpenSSH are ready. Storage access was requested (allow it if Termux asked)."
        ),
        "hermes" to Recipe(
            name = "hermes",
            title = "Hermes Agent (Nous Research)",
            description = "Clones https://github.com/NousResearch/hermes-agent and installs it into a venv with the Termux extras; links the `hermes` command.",
            estimatedTime = "10-20 min",
            interactive = false,
            script = HEADER + """
pkg install -y python git rust clang make binutils libffi openssl
cd "${D}HOME"
if [ -d hermes-agent/.git ]; then
  (cd hermes-agent && git pull --ff-only || true)
else
  rm -rf hermes-agent
  git clone --depth 1 https://github.com/NousResearch/hermes-agent.git
fi
cd "${D}HOME/hermes-agent"
[ -d venv ] || python -m venv venv
. venv/bin/activate
export ANDROID_API_LEVEL="${D}(getprop ro.build.version.sdk)"
python -m pip install --upgrade pip setuptools wheel
if [ -f constraints-termux.txt ]; then
  python -m pip install -e '.[termux]' -c constraints-termux.txt
else
  python -m pip install -e . || python -m pip install -r requirements.txt
fi
if [ -x venv/bin/hermes ]; then
  ln -sf "${D}HOME/hermes-agent/venv/bin/hermes" "${D}PREFIX/bin/hermes"
fi
echo "hermes: done"
hermes --version 2>/dev/null || echo "hermes installed (run: cd ~/hermes-agent && . venv/bin/activate)"
""",
            verifyCommand = "command -v hermes && hermes --version",
            afterNote = "Hermes is installed. First run is interactive (API keys) - open it with termux_open_session(\"hermes\")."
        ),
        "openclaw" to Recipe(
            name = "openclaw",
            title = "OpenClaw (self-hosted AI agent)",
            description = "Installs OpenClaw with the official installer (curl -fsSL https://openclaw.ai/install.sh | bash). Afterwards run `openclaw onboard --install-daemon` in a visible session.",
            estimatedTime = "10-15 min",
            interactive = false,
            script = HEADER + """
pkg install -y curl git nodejs
if command -v openclaw >/dev/null 2>&1; then
  echo "openclaw already installed: ${D}(openclaw --version 2>/dev/null)"
else
  curl -fsSL https://openclaw.ai/install.sh | bash
fi
command -v openclaw >/dev/null 2>&1 && openclaw --version || echo "openclaw: installer finished, open a new session to use it"
echo "openclaw: done"
""",
            verifyCommand = "command -v openclaw && openclaw --version",
            afterNote = "Now run `openclaw onboard --install-daemon` in a visible session (termux_open_session) - it is an interactive wizard. After that `openclaw gateway` starts it and the dashboard is at http://localhost:18789. Tap 'Acquire wakelock' in the Termux notification so Android does not kill it."
        ),
        "ubuntu" to Recipe(
            name = "ubuntu",
            title = "Ubuntu (proot-distro)",
            description = "Installs proot-distro and an Ubuntu rootfs inside Termux. Use termux_run_in_ubuntu afterwards for apt-based tooling.",
            estimatedTime = "15-30 min (downloads ~100 MB)",
            interactive = false,
            script = HEADER + """
pkg install -y proot-distro
if [ -d "${D}PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu" ]; then
  echo "ubuntu already installed"
else
  proot-distro install ubuntu
fi
proot-distro login ubuntu -- bash -c 'apt-get update -y >/dev/null 2>&1 || true; echo "ubuntu: ok"'
echo "ubuntu: done"
""",
            verifyCommand = "[ -d ${D}PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu ] && echo have-ubuntu || echo no-ubuntu",
            afterNote = "Ubuntu is installed. Say 'run this in Ubuntu' and I will use termux_run_in_ubuntu (apt works there, pkg does not)."
        )
    )

    fun get(name: String): Recipe? {
        val key = name.trim().lowercase().replace('_', '-')
        all[key]?.let { return it }
        return when (key) {
            "dev-basics", "devbasics", "basics", "setup", "hunter-setup" -> all["jarvis-setup"]
            "hermes-agent", "nous" -> all["hermes"]
            "open-claw", "claw" -> all["openclaw"]
            "proot", "proot-distro", "linux" -> all["ubuntu"]
            else -> null
        }
    }

    fun names(): List<String> = all.keys.toList()

    /** Remote path the recipe script is written to before being executed. */
    fun scriptPath(recipe: Recipe): String = "$JARVIS_RECIPES_DIR/${recipe.name}.sh"

    /** Status probe ported from Maya's termux_status (prints which tools are present). */
    val STATUS_PROBE: String = """
echo "termux-ok"
for t in python node npm git rg hermes openclaw proot-distro tmux ssh; do
  p=${D}(command -v ${D}t 2>/dev/null) && echo "have ${D}t" || echo "no ${D}t"
done
[ -d ${D}PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu ] && echo "have ubuntu" || echo "no ubuntu"
df -h ${D}HOME 2>/dev/null | tail -1
""".trimIndent()
}
