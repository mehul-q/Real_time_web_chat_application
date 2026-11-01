# Real-time Chat — browser version

This project adds a browser interface and a WebSocket-to-TCP gateway for your existing Java chat server. It keeps the existing line protocol (`LOGIN`, `REGISTER`, `MSG`, `PM`, `FILE`, `USERS`, `HIST`, etc.).

## Requirements

- JDK 11 or newer
- Maven 3.8+
- Your existing Java server support files: `ChatServer.java`, `Protocol.java`, `UserStore.java`, `ChatHistory.java`

The original Java server uses the current working directory for `users.txt` and `chat_history.txt`; run it from the project folder so these files persist there.

## Put the server source files in place

`ChatServer.java`, `Protocol.java`, `UserStore.java`, and `ChatHistory.java` are included in `src/main/java/`. The browser gateway is also included. Keep the classes package-less as provided.

If you have the original console/Swing clients, you can keep them in the same source folder; they are not needed to run the browser UI.

## Run on Windows

1. Open a terminal in this folder.
2. Compile the project:

   ```bat
   mvn package
   ```

3. In terminal window 1, start your original TCP chat server:

   ```bat
   java -cp target\classes ChatServer
   ```

4. In terminal window 2, start the browser gateway:

   ```bat
   mvn exec:java -Dexec.mainClass=WebGateway
   ```

5. Start `WebFiles` too, then open `http://localhost:8080/` in your browser. The WebSocket gateway listens on port `8081`. Open a second incognito/private window or another browser to test a second user.

If Maven does not have the `exec` plugin cached, use:

```bat
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=WebGateway
```

## Run on macOS/Linux

```bash
mvn package
# terminal 1
java -cp target/classes ChatServer
# terminal 2
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=WebGateway
# terminal 3
java -cp target/classes WebFiles
```

Then open `http://localhost:8080/`.

## Important notes

- This is a local-development/demo setup. It is not safe to expose directly to the public internet: use HTTPS/WSS, authentication protections, rate limits, and a persistent host before deployment.
- A hosted deployment needs a process manager or container that starts both `ChatServer` and `WebGateway`, and persistent storage for `users.txt` and `chat_history.txt`.
- Browser file sharing downloads the received file from the message card. The 2 MB limit matches the existing protocol.
- The web login page uses the gateway's same-origin WebSocket endpoint (`/ws`). The Java-WebSocket server handles the WebSocket protocol; static web files are served by the small HTTP server included below.
