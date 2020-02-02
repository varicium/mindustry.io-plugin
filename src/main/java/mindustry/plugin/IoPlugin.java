package mindustry.plugin;

import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.*;

import mindustry.world.Block;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.Role;
import org.json.JSONObject;
import org.json.JSONTokener;

import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.world.Tile;

import static mindustry.Vars.*;

public class IoPlugin extends Plugin {
    public static DiscordApi api = null;
    public static String prefix = ".";
    public static String serverName = "<untitled>";
    private final Long cooldownTime = 300L;
    private final String fileNotFoundErrorMessage = "File not found: config\\mods\\settings.json";
    private JSONObject alldata;
    public static JSONObject data; //token, channel_id, role_id
    private HashMap<Long, String> cooldowns = new HashMap<Long, String>(); //uuid

    //register event handlers and create variables in the constructor
    public IoPlugin() throws InterruptedException {
        try {
            String pureJson = Core.settings.getDataDirectory().child("mods/settings.json").readString();
            alldata = new JSONObject(new JSONTokener(pureJson));
            if (!alldata.has("in-game")){
                Log.err("[ERR!] discordplugin: settings.json has an invalid format!\n");
                //this.makeSettingsFile("settings.json");
                return;
            } else {
                data = alldata.getJSONObject("in-game");
            }
        } catch (Exception e) {
            if (e.getMessage().contains(fileNotFoundErrorMessage)){
                Log.err("[ERR!] discordplugin: settings.json file is missing.\nBot can't start.");
                //this.makeSettingsFile("settings.json");
                return;
            } else {
                Log.err("[ERR!] discordplugin: Init Error");
                e.printStackTrace();
                return;
            }
        }
        try {
            api = new DiscordApiBuilder().setToken(alldata.getString("token")).login().join();
        }catch (Exception e){
            if (e.getMessage().contains("READY packet")){
                Log.err("\n[ERR!] discordplugin: invalid token.\n");
            } else {
                e.printStackTrace();
            }
        }
        BotThread bt = new BotThread(api, Thread.currentThread(), alldata.getJSONObject("discord"));
        bt.setDaemon(false);
        bt.start();

        // setup prefix
        if (data.has("prefix")) {
            prefix = String.valueOf(data.getString("prefix").charAt(0));
        } else {
            Log.warn("[WARN!] discordplugin: no prefix setting detected, using default \".\" prefix.");
        }

        // setup name
        if (data.has("server_name")) {
            prefix = String.valueOf(data.getString("server_name").charAt(0));
        } else {
            Log.warn("[WARN!] discordplugin: no server_name setting detected.");
        }

        // live chat
        if (data.has("live_chat_channel_id")) {
            TextChannel tc = getTextChannel(data.getString("live_chat_channel_id"));
            if (tc != null) {
                HashMap<String, String> messageBuffer = new HashMap<>();
                Events.on(EventType.PlayerChatEvent.class, event -> {
                    messageBuffer.put(Utils.escapeCharacters(event.player.name), Utils.escapeCharacters(event.message));
                    if(messageBuffer.size() >= Utils.messageBufferSize) { // if message buffer size is below the expected size
                        EmbedBuilder eb = new EmbedBuilder().setTitle(new SimpleDateFormat("yyyy_MM_dd").format(Calendar.getInstance().getTime()));
                        for (Map.Entry<String, String> entry : messageBuffer.entrySet()) {
                            String username = entry.getKey();
                            String message = entry.getValue();
                            eb.addField(Utils.escapeCharacters(username), Utils.escapeCharacters(message));
                        }
                        tc.sendMessage(eb);
                        messageBuffer.clear();
                    }
                });
            }
        }

        // anti nuke

        Events.on(EventType.BuildSelectEvent.class, event -> {
            if (Utils.antiNukeEnabled) {
                try {
                    Tile nukeTile = event.builder.buildRequest().tile();
                    if (!event.breaking && event.builder.buildRequest().block == Blocks.thoriumReactor || event.builder.buildRequest().block == Blocks.combustionGenerator || event.builder.buildRequest().block == Blocks.turbineGenerator || event.builder.buildRequest().block == Blocks.impactReactor && event.builder instanceof Player) {
                        Tile coreTile = ((Player) event.builder).getClosestCore().getTile();
                        if (coreTile == null) {
                            return;
                        }
                        double distance = Utils.DistanceBetween(coreTile.x, coreTile.y, nukeTile.x, nukeTile.y);
                        if (distance <= Utils.nukeDistance) {
                            Call.beginBreak(event.builder.getTeam(), event.tile.x, event.tile.y);
                            Call.onDeconstructFinish(event.tile, Blocks.thoriumReactor, ((Player) event.builder).id);
                            ((Player) event.builder).kill();
                            ((Player) event.builder).sendMessage("[scarlet]Too close to the core, please find a better spot.");
                        }
                    }
                } catch (Exception ignored) {}
            }
        });

        if (data.has("warnings_chat_channel_id")) {
            TextChannel tc = this.getTextChannel(data.getString("warnings_chat_channel_id"));
            if (tc != null) {
                Events.on(EventType.WaveEvent.class, event -> {
                    EmbedBuilder eb = new EmbedBuilder().setTitle("Wave " + state.wave + " started.");
                    tc.sendMessage(eb);
                });
                Events.on(EventType.BuildSelectEvent.class, event -> {
                    // we dont want to log all blocks, thats too much.. only suspicious ones
                    if (!event.breaking && event.builder.buildRequest().block == Blocks.thoriumReactor || event.builder.buildRequest().block == Blocks.combustionGenerator || event.builder.buildRequest().block == Blocks.turbineGenerator || event.builder.buildRequest().block == Blocks.impactReactor && event.builder instanceof Player) {
                        Player builder = (Player) event.builder;
                        Block buildBlock = event.builder.buildRequest().block;
                        Tile buildTile = event.builder.buildRequest().tile();
                        EmbedBuilder eb = new EmbedBuilder().setTitle("Suspicious block was placed.");
                        eb.setDescription("Builder: " + Utils.escapeCharacters(builder.name) + " (#" + builder.id + ")");
                        eb.addField(buildBlock.name, "Location: (" + buildTile.x + ", " + buildTile.y + ")");
                        eb.setColor(Utils.Pals.warning);
                        tc.sendMessage(eb);
                    }
                });

                Events.on(EventType.TapConfigEvent.class, event -> {
                    if(event.player!= null) {
                        EmbedBuilder eb = new EmbedBuilder().setTitle("Tile was configured.");
                        eb.setDescription("**Configurator:** " + Utils.escapeCharacters(event.player.name) + " (#" + event.player.id + ")\n");
                        eb.addField(event.tile.block().name, "Location: (" + event.tile.x + ", " + event.tile.y + ")");
                        eb.setColor(Utils.Pals.info);
                        tc.sendMessage(eb);
                    }
                });

            }
        }

        // welcome message
        if(Utils.welcomeMessage!=null) {
            Events.on(EventType.PlayerJoin.class, event -> {
                event.player.sendMessage(Utils.welcomeMessage);
            });
        }

    }

    //register commands that run on the server
    @Override
    public void registerServerCommands(CommandHandler handler){

    }

    //register commands that player can invoke in-game
    @Override
    public void registerClientCommands(CommandHandler handler){
        if (api != null) {
            handler.<Player>register("d", "<text...>", "Sends a message to moderators. (Use when a griefer is online)", (args, player) -> {

                if (!data.has("dchannel_id")) {
                    player.sendMessage("[scarlet]This command is disabled.");
                } else {
                    TextChannel tc = this.getTextChannel(data.getString("dchannel_id"));
                    if (tc == null) {
                        player.sendMessage("[scarlet]This command is disabled.");
                        return;
                    }
                    tc.sendMessage(Utils.escapeCharacters(player.name + " *@mindustry* : `" + args[0] + "`"));
                    player.sendMessage("[scarlet]Successfully sent message to moderators.");
                }

            });

            handler.<Player>register("players", "Display all players and their ids", (args, player) -> {
                StringBuilder builder = new StringBuilder();
                builder.append("[orange]List of players: \n");
                for (Player p : Vars.playerGroup.all()) {
                    if(p.isAdmin) {
                        builder.append("[scarlet]<ADMIN> ");
                    } else{
                        builder.append("[lightgray] ");
                    }
                    builder.append(p.name).append("[accent] (#").append(p.id).append(")\n");
                }
                player.sendMessage(builder.toString());
            });

            handler.<Player>register("gr", "<player> <reason...>", "Report a griefer by id (use '/gr' to get a list of ids)", (args, player) -> {
                //https://github.com/Anuken/Mindustry/blob/master/core/src/io/anuke/mindustry/core/NetServer.java#L300-L351
                if (!(data.has("channel_id") && data.has("role_id"))) {
                    player.sendMessage("[scarlet]This command is disabled.");
                    return;
                }


                for (Long key : cooldowns.keySet()) {
                    if (key + cooldownTime < System.currentTimeMillis() / 1000L) {
                        cooldowns.remove(key);
                        continue;
                    } else if (player.uuid.equals(cooldowns.get(key))) {
                        player.sendMessage("[scarlet]This command is on a 5 minute cooldown!");
                        return;
                    }
                }

                if (args.length == 0) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("[orange]List of players: \n");
                    for (Player p : Vars.playerGroup.all()) {
                        if(p.isAdmin) {
                            builder.append("[scarlet]<ADMIN> ");
                        } else{
                            builder.append("[lightgray] ");
                        }
                        builder.append(p.name).append("[accent] (#").append(p.id).append(")\n");
                    }
                    player.sendMessage(builder.toString());
                } else {
                    Player found = null;
                    if (args[0].length() > 1 && args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))) {
                        int id = Strings.parseInt(args[0].substring(1));
                        for (Player p: Vars.playerGroup.all()){
                            if (p.id == id){
                                found = p;
                                break;
                            }
                        }
                    } else {
                        for (Player p: Vars.playerGroup.all()){
                            if (p.name.equalsIgnoreCase(args[0])){
                                found = p;
                                break;
                            }
                        }
                    }
                    if (found != null) {
                        if (found.isAdmin) {
                            player.sendMessage("[scarlet]Did you really expect to be able to report an admin?");
                        } else if (found.getTeam() != player.getTeam()) {
                            player.sendMessage("[scarlet]Only players on your team can be reported.");
                        } else {
                            TextChannel tc = this.getTextChannel(data.getString("channel_id"));
                            Role r = this.getRole(data.getString("role_id"));
                            if (tc == null || r == null) {
                                player.sendMessage("[scarlet]This command is disabled.");
                                return;
                            }
                            //send message
                            if (args.length > 1) {
                                new MessageBuilder()
                                        .setEmbed(new EmbedBuilder()
                                                .setTitle("A griefer was reported on " + serverName)
                                                .setDescription(r.getMentionTag())
                                                .addField("Name", found.name)
                                                .addField("Reason", args[1])
                                                .setColor(Color.ORANGE)
                                                .setFooter("Reported by " + player.name))
                                        .send(tc);
                            } else {
                                new MessageBuilder()
                                        .setEmbed(new EmbedBuilder()
                                                .setTitle("A griefer was reported on " + serverName)
                                                .setDescription(r.getMentionTag())
                                                .addField("Name", found.name)
                                                .setColor(Color.ORANGE)
                                                .setFooter("Reported by " + player.name))
                                        .send(tc);
                            }
                            player.sendMessage(found.name + "[sky] was reported successfully.");
                            cooldowns.put(System.currentTimeMillis() / 1000L, player.uuid);
                        }
                    }
                }
            });
        }
    }


    public static TextChannel getTextChannel(String id){
        Optional<Channel> dc =  ((Optional<Channel>) api.getChannelById(id));
        if (!dc.isPresent()) {
            Log.err("[ERR!] discordplugin: channel not found!");
            return null;
        }
        Optional<TextChannel> dtc = dc.get().asTextChannel();
        if (!dtc.isPresent()){
            Log.err("[ERR!] discordplugin: textchannel not found!");
            return null;
        }
        return dtc.get();
    }

    public Role getRole(String id){
        Optional<Role> r1 = api.getRoleById(id);
        if (!r1.isPresent()) {
            Log.err("[ERR!] discordplugin: adminrole not found!");
            return null;
        }
        return r1.get();
    }

}