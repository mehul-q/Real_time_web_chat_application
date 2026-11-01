(() => {
  const $ = (id) => document.getElementById(id);
  const loginScreen = $("loginScreen"), chatScreen = $("chatScreen");
  const loginForm = $("loginForm"), loginStatus = $("loginStatus");
  const usernameInput = $("username"), passwordInput = $("password");
  const messages = $("messages"), messageInput = $("messageInput");
  const onlineUsers = $("onlineUsers"), activityList = $("activityList");
  const connectionBadge = $("connectionBadge");
  const fileInput = $("fileInput"), filePreview = $("filePreview");
  let ws = null, username = "", pendingAuth = false, historyMode = false;
  let selectedFile = null, online = [], reconnecting = false;

  function setStatus(text, error = true) {
    loginStatus.textContent = text || "";
    loginStatus.style.color = error ? "#b03a2e" : "#858b95";
  }
  function setConnection(connected) {
    connectionBadge.classList.toggle("offline", !connected);
    connectionBadge.innerHTML = `<i></i> ${connected ? "Connected" : "Disconnected"}`;
  }
  function send(line) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      addSystem("Not connected to the chat server. Refresh and try again.");
      return false;
    }
    ws.send(line);
    return true;
  }
  function connect(register) {
    const user = usernameInput.value.trim(), pass = passwordInput.value;
    if (!/^[A-Za-z0-9_]{2,20}$/.test(user)) return setStatus("Username must be 2–20 characters: letters, numbers or underscore.");
    if (pass.length < 4 || pass.length > 64) return setStatus("Password must be 4–64 characters.");
    username = user;
    pendingAuth = true;
    $("registerBtn").disabled = true;
    loginForm.querySelector('button[type="submit"]').disabled = true;
    setStatus("Connecting to chat server…", false);
    // Same-origin WebSocket; works on localhost and behind an HTTPS reverse proxy.
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    // Local setup uses the UI at :8080 and WebSocket gateway at :8081.
    // For production, configure both behind one HTTPS reverse proxy and use the same host.
    const wsHost = location.hostname + (location.port === "8080" ? ":8081" : (location.port ? ":" + location.port : ""));
    ws = new WebSocket(`${scheme}//${wsHost}/`);
    ws.onopen = () => send(`${register ? "REGISTER" : "LOGIN"}|${user}|${pass}`);
    ws.onmessage = (event) => handleLine(String(event.data));
    ws.onerror = () => {
      if (pendingAuth) setStatus("Could not connect. Make sure ChatServer and WebGateway are running.");
    };
    ws.onclose = () => {
      setConnection(false);
      if (chatScreen.classList.contains("hidden")) {
        $("registerBtn").disabled = false;
        loginForm.querySelector('button[type="submit"]').disabled = false;
      } else addSystem("Connection closed.");
    };
  }
  loginForm.addEventListener("submit", (e) => { e.preventDefault(); connect(false); });
  $("registerBtn").addEventListener("click", () => connect(true));

  function handleLine(line) {
    const p = line.indexOf("|"), command = p < 0 ? line : line.slice(0, p), args = p < 0 ? "" : line.slice(p + 1);
    switch (command) {
      case "AUTH_OK":
        pendingAuth = false;
        passwordInput.value = "";
        loginScreen.classList.add("hidden");
        chatScreen.classList.remove("hidden");
        $("connectedAs").textContent = `· connected as ${args}`;
        $("chatHeading").textContent = "Public chat";
        $("chatSubheading").textContent = "Messages are visible to everyone online";
        $("registerBtn").disabled = false;
        loginForm.querySelector('button[type="submit"]').disabled = false;
        setConnection(true);
        messageInput.focus();
        break;
      case "AUTH_FAIL":
        pendingAuth = false;
        setStatus(args || "Login failed.");
        passwordInput.value = "";
        $("registerBtn").disabled = false;
        loginForm.querySelector('button[type="submit"]').disabled = false;
        if (ws) { ws.close(); ws = null; }
        break;
      case "HIST_BEGIN":
        historyMode = true;
        break;
      case "HIST":
        renderHistory(args);
        break;
      case "HIST_END":
        historyMode = false;
        break;
      case "MSG": {
        const f = splitFields(args, 3);
        if (f.length === 3) addMessage(f[1], f[2], f[0], f[1] === username, false);
        break;
      }
      case "PM": {
        const f = splitFields(args, 3);
        if (f.length === 3) addMessage(f[1], f[2], f[0], false, true);
        break;
      }
      case "PMSENT": {
        const f = splitFields(args, 3);
        if (f.length === 3) addMessage(`To ${f[1]}`, f[2], f[0], true, true);
        break;
      }
      case "JOIN": {
        const f = splitFields(args, 2);
        if (f.length === 2) addActivity(`${f[1]} joined the chat`);
        break;
      }
      case "LEAVE": {
        const f = splitFields(args, 2);
        if (f.length === 2) addActivity(`${f[1]} left the chat`);
        break;
      }
      case "USERS":
        online = args ? args.split(",").filter(Boolean) : [];
        renderUsers();
        break;
      case "FILE": {
        const f = splitFields(args, 4);
        if (f.length === 4) renderFile(f[1], f[2], f[0], f[3]);
        break;
      }
      case "ERROR":
        addSystem(args);
        break;
      default:
        // Unknown lines are ignored instead of breaking the browser UI.
        break;
    }
  }
  function splitFields(value, count) {
    const out = [], first = value;
    let rest = first;
    for (let i = 0; i < count - 1; i++) {
      const pos = rest.indexOf("|");
      if (pos < 0) return [];
      out.push(rest.slice(0, pos));
      rest = rest.slice(pos + 1);
    }
    out.push(rest);
    return out;
  }
  function timeLabel(ms) {
    const date = new Date(Number(ms));
    if (Number.isNaN(date.getTime())) return "";
    const today = new Date();
    return date.toDateString() === today.toDateString()
      ? date.toLocaleTimeString([], {hour:"2-digit", minute:"2-digit"})
      : date.toLocaleString([], {month:"short", day:"numeric", hour:"2-digit", minute:"2-digit"});
  }
  function addMessage(sender, text, ms, mine, isPrivate) {
    const empty = messages.querySelector(".empty-state"); if (empty) empty.remove();
    const article = document.createElement("article");
    article.className = "message" + (mine ? " mine" : "") + (isPrivate ? " private" : "");
    const meta = document.createElement("div"); meta.className = "message-meta";
    const who = document.createElement("strong"); who.textContent = sender;
    const when = document.createElement("span"); when.textContent = timeLabel(ms);
    meta.append(who, when);
    const body = document.createElement("div"); body.className = "message-body"; body.textContent = text;
    article.append(meta, body); messages.append(article); scrollMessages();
  }
  function renderHistory(args) {
    const f = splitFields(args, 3);
    if (f.length === 3) addMessage(f[1], f[2], f[0], f[1] === username, false);
  }
  function renderFile(sender, name, ms, base64) {
    const empty = messages.querySelector(".empty-state"); if (empty) empty.remove();
    const article = document.createElement("article"); article.className = "message" + (sender === username ? " mine" : "");
    const meta = document.createElement("div"); meta.className = "message-meta";
    const who = document.createElement("strong"); who.textContent = sender;
    const when = document.createElement("span"); when.textContent = timeLabel(ms);
    meta.append(who, when);
    const body = document.createElement("div"); body.className = "message-body"; body.textContent = `Shared a file: ${name}`;
    const link = document.createElement("a"); link.className = "file-link"; link.textContent = "Download file";
    link.href = `data:application/octet-stream;base64,${base64}`;
    link.download = safeFilename(name);
    article.append(meta, body, link); messages.append(article); scrollMessages();
  }
  function safeFilename(name) {
    return (name || "file").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").replace(/^\.+/, "").slice(0, 100) || "file";
  }
  function addSystem(text) {
    const node = document.createElement("div"); node.className = "system-message"; node.textContent = text;
    messages.append(node); scrollMessages();
  }
  function addActivity(text) {
    const li = document.createElement("li"), dot = document.createElement("span");
    dot.className = "activity-dot";
    const label = document.createElement("span"); label.textContent = text;
    li.append(dot, label); activityList.prepend(li);
    while (activityList.children.length > 30) activityList.lastElementChild.remove();
  }
  function renderUsers() {
    onlineUsers.replaceChildren(); $("onlineCount").textContent = online.length;
    online.forEach(name => {
      const li = document.createElement("li");
      const dot = document.createElement("span"); dot.className = "online-dot";
      const label = document.createElement("span"); label.className = "online-name"; label.textContent = name;
      li.append(dot, label);
      if (name.toLowerCase() === username.toLowerCase()) {
        const you = document.createElement("span"); you.className = "you-tag"; you.textContent = "(you)"; li.append(you);
      } else li.title = "Click to start a private message";
      li.addEventListener("dblclick", () => { if (name.toLowerCase() !== username.toLowerCase()) messageInput.value = `/msg ${name} `, messageInput.focus(); });
      li.addEventListener("click", () => { if (name.toLowerCase() !== username.toLowerCase()) { messageInput.value = `/msg ${name} `; messageInput.focus(); } });
      onlineUsers.append(li);
    });
  }
  function scrollMessages() { messages.scrollTop = messages.scrollHeight; }
  function sendMessage(e) {
    e.preventDefault();
    const text = messageInput.value.trim();
    if (!text) return;
    if (text === "/users") { addSystem("Online users are listed in the sidebar."); messageInput.value = ""; return; }
    if (text === "/help") { addSystem("Send a public message, or use /msg username message for a private message. Use Attach to share a file (up to 2 MB)."); messageInput.value = ""; return; }
    if (text === "/quit") { logout(); return; }
    if (send(text.startsWith("/msg ") ? "PM|" + text.slice(5) : "MSG|" + text)) messageInput.value = "";
  }
  $("sendForm").addEventListener("submit", sendMessage);
  $("publicChatBtn").addEventListener("click", () => {
    $("chatHeading").textContent = "Public chat";
    $("chatSubheading").textContent = "Messages are visible to everyone online";
    messageInput.placeholder = "Write a message…";
    messageInput.focus();
  });
  $("attachBtn").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    selectedFile = fileInput.files && fileInput.files[0] || null;
    if (!selectedFile) { filePreview.classList.add("hidden"); return; }
    if (selectedFile.size < 1 || selectedFile.size > 2 * 1024 * 1024) {
      addSystem("Files must be between 1 byte and 2 MB.");
      fileInput.value = ""; selectedFile = null; filePreview.classList.add("hidden"); return;
    }
    filePreview.classList.remove("hidden");
    filePreview.textContent = `${selectedFile.name} (${formatSize(selectedFile.size)}) `;
    const remove = document.createElement("button"); remove.textContent = "Remove";
    remove.addEventListener("click", () => { selectedFile = null; fileInput.value = ""; filePreview.classList.add("hidden"); });
    filePreview.append(remove);
  });
  function formatSize(bytes) { return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(0)} KB`; }
  async function uploadFile() {
    if (!selectedFile) return true;
    const file = selectedFile;
    const dataUrl = await new Promise((resolve, reject) => {
      const reader = new FileReader(); reader.onerror = () => reject(new Error("Could not read file"));
      reader.onload = () => resolve(String(reader.result).split(",")[1]); reader.readAsDataURL(file);
    });
    if (!send(`FILE|${safeFilename(file.name)}|${dataUrl}`)) return false;
    selectedFile = null; fileInput.value = ""; filePreview.classList.add("hidden");
    return true;
  }
  $("sendForm").addEventListener("submit", async (e) => {
    if (!selectedFile) return;
    e.preventDefault();
    try { await uploadFile(); } catch (err) { addSystem(err.message || "File upload failed."); }
  });
  function logout() {
    if (ws && ws.readyState === WebSocket.OPEN) send("QUIT");
    if (ws) ws.close();
    ws = null; username = ""; pendingAuth = false;
    chatScreen.classList.add("hidden"); loginScreen.classList.remove("hidden");
    messages.replaceChildren(); onlineUsers.replaceChildren(); activityList.replaceChildren();
    setStatus(""); setConnection(false); passwordInput.value = ""; messageInput.value = "";
    selectedFile = null; filePreview.classList.add("hidden");
  }
  $("logoutBtn").addEventListener("click", logout);
  messages.innerHTML = '<div class="empty-state">No messages yet.<br>Say hello to start the conversation.</div>';
  setConnection(false);
})(); 
