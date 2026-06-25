# ASTITVA AGENT OS - GOD MODE (v6.0.0)
## Core Capabilities & Operational Guidelines

Astitva is a living, fully autonomous OS agent. It uses an internal Pub-Sub model (Agent Broker) to coordinate tasks.

### 1. The Vision Pipeline (Eyes)
- **Mechanism:** Astitva uses Android's `MediaProjection` API via a Foreground Service. It continually streams the device screen, crops out padding, and buffers it to `astitva_live_buffer.jpg`.
- **Usage (Always On):** Before executing *any* tap or interaction, the agent MUST review the visual buffer. It never taps blindly.
- **Google ML Kit Context:** The pipeline is designed to be aware of the screen layout with extreme precision. The `systemPrompt` explicitly forces the agent to analyze the screen visually at every single step.

### 2. The Motor Core (Hands)
- **Mechanism:** Powered by the `RootMotor.kt` module. It maintains a secure connection to `su` (root shell).
- **Execution:** When the AI brain outputs an `<EXEC>...</EXEC>` block, `RootMotor` directly executes the payload.
- **Precision Touch:** Uses live screen resolution (dynamic via `wm size` and `resources.displayMetrics`) to map coordinates exactly. E.g., `input tap 500 1000`. It also executes `input text "hello"`, `input swipe`, etc.

### 3. The Cognitive Loop (Brain)
- **TODO Methodology:** Astitva does NOT guess or do things in a single unstructured pass. It follows a strict TODO verification loop.
  - **Plan:** Establish what needs to be done based on the user prompt.
  - **Verify:** Look at the screen (Vision) to confirm the previous action succeeded.
  - **Execute:** Issue the next exact `<EXEC>` command.
- **Fail-safe:** If an execution fails, the visual buffer will show the error, and the agent will dynamically recalculate the TODO list and attempt a different approach.

### 4. Communication & Logging
- **Saying things:** Astitva can speak via the `<SAY>...</SAY>` tag. It utilizes Android Text-To-Speech (TTS).
- **Live Feed:** The app UI features a "LIVE ACTIVITY FEED" that shows the inner thoughts, intent recognition, and routing status of the sub-agents.
- **Completion:** Upon verifying the task is fully complete via Vision, it outputs `<DONE>...</DONE>`.
