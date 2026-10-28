package kr.jobnations;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

public final class JobNationsPlugin extends JavaPlugin {
    private JobManager jobs;
    private JobListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        jobs = new JobManager(this);
        jobs.load();

        listener = new JobListener(this, jobs);
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand cmd = getCommand("job");
        if (cmd != null) {
            JobCommand jc = new JobCommand(this, jobs);
            cmd.setExecutor(jc);
            cmd.setTabCompleter(jc);
        }

        getServer().getScheduler().runTaskTimer(this, this::applyPassives, 40L, 40L);
        long interval = Math.max(5, getConfig().getInt("economy.payout-interval-seconds", 30)) * 20L;
        getServer().getScheduler().runTaskTimer(this, this::payoutTick, interval, interval);
        getServer().getScheduler().runTaskTimer(this, listener::prune, 6000L, 6000L);
        getLogger().info("JobNations 활성화");
    }

    @Override
    public void onDisable() {
        if (jobs != null) jobs.save();
    }

    private void payoutTick() {
        for (Player p : Bukkit.getOnlinePlayers()) flushPending(p);
        jobs.saveIfDirty();
    }

    /** 모인 건축 수익을 돈 스크립트로 정산 (1원 이상일 때만) */
    public void flushPending(Player p) {
        if (!getConfig().getBoolean("economy.enabled", true)) return;
        UUID id = p.getUniqueId();
        int amount = (int) Math.floor(jobs.getPending(id));
        if (amount < 1) return;
        String cmd = getConfig().getString("economy.pay-command", "돈 정산 {player} {amount}")
                .replace("{player}", p.getName())
                .replace("{amount}", String.valueOf(amount));
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        if (ok) {
            jobs.addPending(id, -amount);
        } else {
            getLogger().warning("정산 명령어 실행 실패: " + cmd);
        }
    }

    private void applyPassives() {
        boolean nightVision = getConfig().getBoolean("merit.miner-night-vision", true);
        boolean dolphin = getConfig().getBoolean("merit.fisher-dolphins-grace", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Job job = jobs.getJob(p.getUniqueId());
            if (job == null) continue;

            if (job == Job.MINER && nightVision
                    && p.getWorld().getEnvironment() == World.Environment.NORMAL
                    && p.getLocation().getBlock().getLightFromSky() < 4) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, true, false, false));
            }
            if (job == Job.FISHER && dolphin && p.isInWater()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 100, 0, true, false, false));
            }
        }
    }
}
