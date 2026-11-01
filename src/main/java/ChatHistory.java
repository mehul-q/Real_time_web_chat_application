import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

class ChatHistory {
    private final Path file;
    private final int keep;
    private final LinkedList<String> recent = new LinkedList<>();
    ChatHistory(String fileName, int keep) { this.file = Paths.get(fileName); this.keep = keep; load(); }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (Protocol.fields(line, 3).length == 3) remember(line);
            }
        } catch (IOException e) { System.out.println("Warning: could not read " + file + " (" + e.getMessage() + ")"); }
    }
    private void remember(String line) { recent.addLast(line); while (recent.size() > keep) recent.removeFirst(); }
    synchronized void add(long time, String sender, String text) {
        String line = Protocol.join(String.valueOf(time), sender, text);
        remember(line);
        try {
            Files.write(file, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { System.out.println("Warning: could not save chat history (" + e.getMessage() + ")"); }
    }
    synchronized List<String> recent() { return new ArrayList<>(recent); }
}
