/**
 * EvidenceTail.java follows the clean reporter's rotating JSONL files.
 * Existing bytes are skipped at startup so old alerts are not presented as live ones.
 */

package dev.fox.monitor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class EvidenceTail{
    private final Path directory;
    private final Consumer<String> record;
    private final Consumer<String> notice;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private String lastError = "";

    private static final class FileState{
        Path path;
        String key;
        long size;
        long modified;
    }

    private static final class Cursor{
        long offset;
        LineReader lines;
    }

    EvidenceTail(Path directory, Consumer<String> record, Consumer<String> notice){
        this.directory = directory;
        this.record = record;
        this.notice = notice;
    }

    private FileState state(Path path) throws IOException{
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        FileState state = new FileState();
        state.path = path;
        Object key = attributes.fileKey();
        state.key = key == null
            ? path.toString() + ":" + attributes.creationTime().toString()
            : key.toString();
        state.size = attributes.size();
        state.modified = attributes.lastModifiedTime().toMillis();
        return state;
    }

    private List<FileState> files() throws IOException{
        List<FileState> files = new ArrayList<>();

        for(int i = 0; i < 3; ++i){
            Path path = directory.resolve("findings-" + i + ".jsonl");

            if(!Files.isRegularFile(path))
                continue;

            try{
                files.add(state(path));
            }catch(java.nio.file.NoSuchFileException ignored){
                // A rotation can move the file between directory lookup and stat.
            }
        }

        files.sort(Comparator.comparingLong(file->file.modified));
        return files;
    }

    // Call before starting Spigot; only newly appended records belong to this run.
    void prime() throws IOException{
        cursors.clear();

        for(FileState file : files()){
            Cursor cursor = cursor();
            cursor.offset = file.size;
            cursors.put(file.key, cursor);
        }
    }

    private Cursor cursor(){
        Cursor cursor = new Cursor();
        cursor.lines = new LineReader(record);
        return cursor;
    }

    // Open briefly per poll so the monitor does not hold a Windows rotation lock.
    void poll(){
        try{
            for(FileState file : files()){
                Cursor cursor = cursors.get(file.key);

                if(cursor == null){
                    cursor = cursor();
                    cursors.put(file.key, cursor);
                }

                if(file.size < cursor.offset){
                    cursor = cursor();
                    cursors.put(file.key, cursor);
                    notice.accept("Evidence file was truncated; continuing from its beginning.");
                }

                int count = (int)Math.min(1024 * 1024, file.size - cursor.offset);

                if(count <= 0)
                    continue;

                byte[] bytes = new byte[count];
                int read;

                try(RandomAccessFile input = new RandomAccessFile(file.path.toFile(), "r")){
                    input.seek(cursor.offset);
                    read = input.read(bytes);
                }catch(java.io.FileNotFoundException ignored){
                    continue;
                }

                FileState after;

                try{
                    after = state(file.path);
                }catch(java.nio.file.NoSuchFileException ignored){
                    continue;
                }

                if(!after.key.equals(file.key) || read <= 0)
                    continue;

                cursor.offset += read;
                cursor.lines.accept(bytes, read);
            }

            while(cursors.size() > 16)
                cursors.remove(cursors.keySet().iterator().next());

            lastError = "";
        }catch(IOException | RuntimeException error){
            String message = error.toString();

            if(!message.equals(lastError)){
                notice.accept("Evidence feed error: " + message);
                lastError = message;
            }
        }
    }
}
