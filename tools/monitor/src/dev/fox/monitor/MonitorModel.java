/**
 * MonitorModel.java keeps a bounded view of received findings and server logs.
 * Only the Swing thread updates this state; network/game state stays in the server.
 */

package dev.fox.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MonitorModel{
    static final int ACTIVITY_LIMIT = 5000;
    static final int ALERT_LIMIT = 2000;
    static final int LOG_LIMIT = 6000;
    static final int PLAYER_LIMIT = 4096;
    static final Pattern PREFIX = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*){1,2}:?\\s*\\[FoxAntiCheat\\]\\s*(.*)$");
    static final Pattern UUID = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*){1,2}:?\\s*UUID of player ([A-Za-z0-9_]+) is ([a-fA-F0-9-]{36})$");
    static final Pattern TIME = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})");
    static final Pattern LOGIN = Pattern.compile("^.*?\\]:? ([A-Za-z0-9_]+)\\[.*?logged in with entity id .*$");
    static final Pattern LEAVE = Pattern.compile("^.*?\\]:? ([A-Za-z0-9_]+) lost connection:.*$");

    final Deque<String[]> responses = new ArrayDeque<>();
    long bans;
    String policy = "Policy not observed";
    private final java.util.Set<String> banIds = new java.util.LinkedHashSet<>();

    private void response(String time, String player, String action, String evidence, String raw) {
        responses.addFirst(new String[]{time, player, action, evidence, raw});
        while(responses.size() > 2000) responses.removeLast();
        ++recordVersion;
    }

    final Deque<Finding> activity = new ArrayDeque<>();
    final Deque<Finding> alerts = new ArrayDeque<>();
    final Deque<String> logs = new ArrayDeque<>();
    final Map<String, Player> players = new LinkedHashMap<>();
    final Map<String, String> names = new LinkedHashMap<>();
    final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    private long activityChars;
    private long alertChars;
    private long seenChars;
    private long logChars;
    long received;
    long suspicious;
    long logVersion;
    long recordVersion;
    long malformed;
    long uiDropped;
    int warnings;
    String engineState = "Waiting";
    String feed = "Waiting for findings";
    boolean ready;

    static final class Player{
        final String uuid;
        String name;
        String status = "Observed";
        String last = "--:--:--";
        long alerts;
        long records;

        Player(String uuid, String name){
            this.uuid = uuid;
            this.name = name;
        }
    }

    void log(String raw){
        String line = raw.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        line = line.replaceAll("[\\p{Cntrl}&&[^\\t]]", "");
        logs.addLast(line);
        logChars += line.length();

        while(logs.size() > LOG_LIMIT || logChars > 1_000_000)
            logChars -= logs.removeFirst().length();

        ++logVersion;

        if(line.contains("Done (") && line.contains("For help, type"))
            ready = true;

        if(line.matches("^\\[.*?(?:WARN|ERROR|SEVERE|FATAL).*?\\].*"))
            ++warnings;

        Matcher identity = UUID.matcher(line);

        if(identity.matches()){
            names.put(identity.group(2), identity.group(1));
            trimNames();
            Player player = players.get(identity.group(2));

            if(player == null){
                if(players.size() >= PLAYER_LIMIT)
                    players.remove(players.keySet().iterator().next());

                player = new Player(identity.group(2), identity.group(1));
                player.status = "Login observed";
                players.put(player.uuid, player);
            }else{
                player.name = identity.group(1);
            }
        }

        Matcher login = LOGIN.matcher(line);
        Matcher leave = LEAVE.matcher(line);

        if(login.matches())
            statusByName(login.group(1), "Online");
        else if(leave.matches())
            statusByName(leave.group(1), "Offline");

        Matcher plugin = PREFIX.matcher(line);

        if(!plugin.matches())
            return;

        String body = plugin.group(1);

        Matcher timestamp = TIME.matcher(line);
        String at = timestamp.find() ? timestamp.group(1) : "--:--:--";
        if(body.startsWith("Timer budget enforcement:")) policy = body;
        if(body.startsWith("BAN | ")) {
            String[] fields = body.split("\\s*\\|\\s*", 4);
            if(fields.length == 4 && fields[3].startsWith("evidence=")) {
                String id = fields[3].substring(9);
                if(banIds.add(id)) {
                    ++bans;
                    while(banIds.size() > 4000) banIds.remove(banIds.iterator().next());
                    response(at, fields[1], "BANNED", id, line);
                    statusByName(fields[1], "Banned");
                }
            }
        } else if(body.startsWith("SUSPICIOUS | ")) {
            String[] fields = body.split("\\s*\\|\\s*", 4);
            if(fields.length == 4) response(at, fields[1], "DETECTED", fields[2], line);
        }
        if(body.startsWith("C++ detection engine loaded.") || body.startsWith("C++ engine loaded."))
            engineState = "Initialized";
        else if(body.contains("Could not enable native anticheat"))
            engineState = "Startup failed";
        else if(body.contains("Native processing disabled") || body.contains("Observer disabled"))
            engineState = "Session error";

        if(body.startsWith("{")){
            Matcher time = TIME.matcher(line);
            finding(body, "Console JSON", time.find() ? time.group(1) : null);
        }
    }

    // Console JSON and evidence-file JSON can contain the same Finding. Count it once.
    void finding(String json, String source, String time){
        if(seen.containsKey(json))
            return;

        Finding finding;

        try{
            finding = Finding.read(json);
        }catch(IllegalArgumentException error){
            ++malformed;
            return;
        }

        finding.sourceTime = time;
        seen.put(json, Boolean.TRUE);
        seenChars += json.length();

        while(seen.size() > 12000 || seenChars > 4_000_000){
            String oldest = seen.keySet().iterator().next();
            seen.remove(oldest);
            seenChars -= oldest.length();
        }

        activity.addFirst(finding);
        activityChars += json.length();

        while(activity.size() > ACTIVITY_LIMIT || activityChars > 2_000_000)
            activityChars -= activity.removeLast().json.length();

        ++received;
        ++recordVersion;
        feed = source;

        String uuid = finding.field("player");
        Player player = players.get(uuid);

        if(player == null){
            if(players.size() >= PLAYER_LIMIT)
                players.remove(players.keySet().iterator().next());

            player = new Player(uuid, names.get(uuid));
            players.put(uuid, player);
        }

        player.records++;
        player.last = finding.time();

        if(finding.field("message").equals("session_open"))
            player.status = "Session open";
        else if(finding.field("message").equals("session_closed") && !player.status.equals("Banned"))
            player.status = "Offline";

        if(finding.suspicious()){
            ++suspicious;
            ++player.alerts;
            alerts.addFirst(finding);
            alertChars += json.length();

            while(alerts.size() > ALERT_LIMIT || alertChars > 2_000_000)
                alertChars -= alerts.removeLast().json.length();
        }
    }

    private void trimNames(){
        while(names.size() > PLAYER_LIMIT)
            names.remove(names.keySet().iterator().next());
    }

    private void statusByName(String name, String status){
        for(Player player : players.values()){
            if(name.equals(player.name))
                player.status = status;
        }
    }

    String playerName(Finding finding){
        String uuid = finding.field("player");
        String name = names.get(uuid);
        return name == null ? uuid : name;
    }

    int flaggedPlayers(){
        int count = 0;

        for(Player player : players.values()){
            if(player.alerts > 0)
                ++count;
        }

        return count;
    }

    List<Finding> matching(boolean alertsOnly, String query){
        List<Finding> result = new ArrayList<>();
        String text = query.toLowerCase(java.util.Locale.ROOT);

        for(Finding finding : alertsOnly ? alerts : activity){
            if(text.isEmpty() || (playerName(finding) + " " + finding.json)
                .toLowerCase(java.util.Locale.ROOT).contains(text)){
                result.add(finding);
            }
        }

        return result;
    }
}
