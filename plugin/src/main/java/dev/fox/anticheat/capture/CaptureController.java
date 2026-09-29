package dev.fox.anticheat.capture;

import dev.fox.anticheat.Session;
import dev.fox.anticheat.bridge.EventWriter;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread lab controls. Labels are operator declarations, never detector findings. */
public final class CaptureController implements AutoCloseable,CommandExecutor {
    private final CaptureRecorder recorder;
    private final Map<Long,String> active=new HashMap<>();
    private final Set<Long> collected=new HashSet<>();
    private final Map<Long,byte[]> starts=new HashMap<>();
    private final Function<UUID,Session> sessions;
    private final LongSupplier clock;
    private final JavaPlugin plugin;
    private final String bootId;

    public boolean isCollectionSession(UUID player){
        Session session=sessions.apply(player);
        return session!=null && collected.contains(session.id);
    }

    public CaptureController(JavaPlugin plugin,Function<UUID,Session> sessions,LongSupplier clock)throws Exception{
        this.plugin=plugin;this.sessions=sessions;this.clock=clock;
        File file=new File(plugin.getDataFolder(),"capture.properties");
        if(!file.isFile())plugin.saveResource("capture.properties",false);
        Properties p=new Properties();try(InputStream in=Files.newInputStream(file.toPath())){p.load(in);}
        bootId="run-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        if(!Boolean.parseBoolean(p.getProperty("enabled","false"))){recorder=null;return;}
        Path root=Paths.get(p.getProperty("directory",new File(plugin.getDataFolder(),"datasets").getPath()));
        long quota=Long.parseLong(p.getProperty("quota-gib","20"))*1024L*1024*1024;
        if(quota<1024L*1024*1024||quota>2048L*1024*1024*1024)throw new IllegalArgumentException("quota-gib must be 1..2048");
        Map<String,Object> provenance=new LinkedHashMap<>();
        provenance.put("server_version",plugin.getServer().getVersion());provenance.put("java",System.getProperty("java.version"));
        provenance.put("created_epoch_ms",System.currentTimeMillis());provenance.put("label_source","operator_declared; review required");
        provenance.put("server_jar_sha256",hash(Paths.get(p.getProperty("server-jar","spigot-1.8.8.jar")))) ;
        provenance.put("plugin_sha256",hash(Paths.get(CaptureController.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        provenance.put("native_sha256",hash(new File(plugin.getDataFolder(),System.mapLibraryName("anticheat_native")).toPath()));
        provenance.put("engine_config_sha256",hash(new File(plugin.getDataFolder(),"engine.conf").toPath()));
        recorder=new CaptureRecorder(root,bootId,provenance,quota,64L*1024*1024,2048,plugin.getLogger());
    }
    private static String hash(Path p)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];
        try(InputStream in=Files.newInputStream(p)){int n;while((n=in.read(block))!=-1)md.update(block,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:md.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public void accept(ByteBuffer event){
        if(recorder==null)return;
        ByteBuffer b=event.duplicate().order(ByteOrder.LITTLE_ENDIAN);int type=b.getShort(6)&65535;long session=b.getLong(8);
        if(type==EventWriter.START){byte[] raw=new byte[b.remaining()];b.get(raw);starts.put(session,raw);}
        String id=active.get(session);if(id!=null)recorder.append(id,event);
        if(type==EventWriter.END){stop(session,"disconnect");starts.remove(session);collected.remove(session);}
    }
    private void stop(long session,String reason){
        String id=active.remove(session);if(id!=null)recorder.end(id,clock.getAsLong(),System.currentTimeMillis(),reason);
    }
    private static String token(String s){
        if(!s.matches("[A-Za-z0-9_.+=,:-]{1,80}"))throw new IllegalArgumentException("Use 1-80 letters/digits/_.+=,:- per metadata field");return s;
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("foxanticheat.capture")){sender.sendMessage("Capture commands require foxanticheat.capture (console or an operator).");return true;}
        try{
            if(recorder==null){sender.sendMessage("Capture disabled in capture.properties; restart server after enabling.");return true;}
            if(args.length==0||args[0].equalsIgnoreCase("status")){
                sender.sendMessage("Capture: "+recorder.directory()+" active="+active.size()+" written="+recorder.written()+" queued="+recorder.queued()+" error="+recorder.failure());return true;
            }
            if(args[0].equalsIgnoreCase("arena")){
                World world=plugin.getServer().getWorld("ac_collection_lab");
                if(world==null)throw new IllegalArgumentException("Start the prepared collection world first");
                File marker=new File(plugin.getDataFolder(),"arena-"+world.getUID()+".ready");
                if(!marker.exists()){
                    world.setGameRuleValue("doMobSpawning","false");world.setGameRuleValue("doDaylightCycle","false");
                    world.setGameRuleValue("keepInventory","true");world.setTime(6000);world.setPVP(true);
                    for(int x=-10;x<=60;x++)for(int z=-4;z<=4;z++)world.getBlockAt(x,4,z).setType(Material.STONE);
                    for(int x=0;x<=50;x+=5)world.getBlockAt(x,4,0).setType(Material.WOOD);
                    for(int step=0;step<20;step++)for(int z=-1;z<=1;z++)world.getBlockAt(18+step,5+step,z).setType(Material.STONE);
                    for(int x=38;x<=42;x++)for(int z=-2;z<=2;z++)world.getBlockAt(x,24,z).setType(Material.STONE);
                    world.setSpawnLocation(0,5,0);Files.write(marker.toPath(),world.getUID().toString().getBytes("UTF-8"));
                }
                if(args.length>=2){
                    Player p=plugin.getServer().getPlayerExact(args[1]);if(p==null)throw new IllegalArgumentException("Player must be online");
                    boolean fall=args.length>=3&&args[2].equals("fall");
                    p.teleport(new Location(world,fall?40.5:.5,fall?25:5,.5));
                }
                sender.sendMessage("Collection lane ready; fall platform at 40,25,0. Start recording after moving into position.");return true;
            }
            if(args[0].equalsIgnoreCase("stopall")){for(long id:new ArrayList<>(active.keySet()))stop(id,"operator_stop");sender.sendMessage("All trials queued for completion.");return true;}
            if(args.length<2)throw new IllegalArgumentException("Missing player name");
            Player player=plugin.getServer().getPlayerExact(args[1]);
            if(player==null)throw new IllegalArgumentException("Player must be online with exact name");
            Session s=sessions.apply(player.getUniqueId());if(s==null||!s.active)throw new IllegalArgumentException("No healthy observation session");
            if(args[0].equalsIgnoreCase("stop")){stop(s.id,"operator_stop");sender.sendMessage("Trial queued for completion: "+player.getName());return true;}
            if(!args[0].equalsIgnoreCase("start")||args.length!=7)throw new IllegalArgumentException("Usage: acdata start PLAYER legit|cheat|unknown MODULE CLIENT SETTING SCENARIO");
            if(recorder.failure()!=null)throw new IllegalArgumentException("Recorder failed: "+recorder.failure());
            if(active.containsKey(s.id))throw new IllegalArgumentException("Stop the existing trial first");
            if(active.size()>=32)throw new IllegalArgumentException("Too many simultaneous trials");
            String declared=token(args[2]);if(!Arrays.asList("legit","cheat","unknown").contains(declared))throw new IllegalArgumentException("Invalid label");
            if(declared.equals("legit")&&!args[3].equals("none"))throw new IllegalArgumentException("Legit controls must use module=none");
            if(declared.equals("cheat")&&args[3].equals("none"))throw new IllegalArgumentException("Specify enabled module");
            if(!starts.containsKey(s.id))throw new IllegalArgumentException("Reconnect to establish a captured SessionStart");
            String id="trial-"+UUID.randomUUID();Map<String,Object> m=new LinkedHashMap<>();
            m.put("boot_id",bootId);m.put("player_uuid",player.getUniqueId().toString());m.put("session_id",s.id);
            m.put("declared_label",declared);m.put("module",token(args[3]));m.put("client",token(args[4]));
            m.put("setting",token(args[5]));m.put("scenario",token(args[6]));m.put("verification","unreviewed");
            m.put("start_observed_ns",clock.getAsLong());m.put("start_epoch_ms",System.currentTimeMillis());m.put("guard_ms",5000);
            if(!recorder.begin(id,m))throw new IllegalArgumentException("Recorder rejected start");
            active.put(s.id,id);collected.add(s.id);recorder.append(id,ByteBuffer.wrap(starts.get(s.id)));
            sender.sendMessage("Recording "+id+" for "+player.getName()+". Label is operator-declared; review before training.");
        }catch(Exception e){sender.sendMessage("Capture: "+e.getMessage());}
        return true;
    }
    @Override public void close(){
        if(recorder==null)return;
        for(long id:new ArrayList<>(active.keySet()))stop(id,"server_stop");recorder.close();starts.clear();
    }
}
