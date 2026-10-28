package kr.jobnations;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class JobListener implements Listener {
    private record PaidBlock(UUID owner, double won, int xp, long time) {}

    private final JobNationsPlugin plugin;
    private final JobManager jobs;
    private final NamespacedKey spawnerKey;
    /** 플레이어가 설치한 광물/수박/호박/사탕수수 위치 */
    private final Map<String, Long> marks = new ConcurrentHashMap<>();
    /** 건축 수익을 이미 지급한 블록 위치 */
    private final Map<String, PaidBlock> paid = new ConcurrentHashMap<>();
    private final Set<UUID> capShown = new HashSet<>();

    public JobListener(JobNationsPlugin plugin, JobManager jobs) {
        this.plugin = plugin;
        this.jobs = jobs;
        this.spawnerKey = new NamespacedKey(plugin, "spawner");
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    private static boolean chance(double c) {
        return c > 0 && ThreadLocalRandom.current().nextDouble() < c;
    }

    private static void bar(Player p, String text, NamedTextColor color) {
        p.sendActionBar(Component.text(text, color));
    }

    private long ttl() {
        return (long) (cfg().getDouble("anti-exploit-hours", 24) * 3_600_000L);
    }

    private static String key(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    public void prune() {
        long now = System.currentTimeMillis();
        long t = ttl();
        marks.values().removeIf(v -> now - v > t);
        paid.values().removeIf(v -> now - v.time() > t);
        capShown.clear();
    }

    private void gainXp(Player p, Job job, int xp) {
        int up = jobs.addXp(p.getUniqueId(), job, xp);
        if (up > 0) {
            int lv = jobs.getLevel(p.getUniqueId(), job);
            p.sendMessage(Component.text("★ " + job.display() + " 레벨 업! Lv." + lv, NamedTextColor.GOLD));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    private static boolean isMatureCrop(Block b) {
        switch (b.getType()) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART:
                return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
            case MELON, PUMPKIN, SUGAR_CANE:
                return true;
            default:
                return false;
        }
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private static boolean isMarkable(Material m) {
        return isOre(m) || m == Material.MELON || m == Material.PUMPKIN || m == Material.SUGAR_CANE;
    }

    /** 블록 1개의 가치(다이아 단위). 0이면 수익 없음 */
    private double blockDiamonds(Material m) {
        String n = m.name();
        for (String k : cfg().getStringList("economy.no-pay-keywords")) {
            if (n.contains(k)) return 0;
        }
        for (String k : cfg().getStringList("economy.premium-keywords")) {
            if (n.contains(k)) return cfg().getDouble("economy.premium-block-diamonds", 0.02);
        }
        return cfg().getDouble("economy.block-diamonds", 0.01);
    }

    // ---------- 접속 / 퇴장 ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        jobs.touchName(p.getUniqueId(), p.getName());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.flushPending(p);
        }, 100L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.flushPending(e.getPlayer());
    }

    // ---------- 스포너 몹 표시 (경험치 농장 방지) ----------
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            e.getEntity().getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    // ---------- 농부 / 광부 ----------
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        Job job = jobs.getJob(p.getUniqueId());
        if (job == null) return;

        Block b = e.getBlock();
        boolean crop = isMatureCrop(b);
        boolean ore = !crop && isOre(b.getType());
        if (!crop && !ore) return;

        // 플레이어가 설치했던 블록이면 보상도 패널티도 없음
        Long mark = marks.remove(key(b));
        if (mark != null && System.currentTimeMillis() - mark < ttl()) return;

        Job owner = crop ? Job.FARMER : Job.MINER;
        double bonus = cfg().getDouble(crop ? "merit.farmer-crop-bonus-chance" : "merit.miner-ore-bonus-chance", 0.4);
        double loss = cfg().getDouble(crop ? "penalty.farmer-crop-loss-chance" : "penalty.miner-ore-loss-chance", 0.35);

        if (job == owner) {
            double sc = jobs.scale(p.getUniqueId(), job);
            if (chance(Math.min(0.95, bonus * sc))) {
                ItemStack tool = p.getInventory().getItemInMainHand();
                for (ItemStack drop : b.getDrops(tool, p)) {
                    b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), drop.clone());
                }
                bar(p, "[" + job.display() + "] 추가 드롭!", NamedTextColor.GREEN);
            }
            gainXp(p, job, cfg().getInt(crop ? "xp.farmer-crop" : "xp.miner-ore", 1));
        } else if (chance(loss)) {
            e.setDropItems(false);
            bar(p, owner.display() + "의 일이라 서툴러서 드롭을 잃었습니다.", NamedTextColor.RED);
        }
    }

    /** 건축가가 설치했던 블록을 직접 부수면 수익/경험치 회수 (설치-철거 반복 방지) */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreakClaw(BlockBreakEvent e) {
        PaidBlock pb = paid.remove(key(e.getBlock()));
        if (pb == null) return;
        if (!pb.owner().equals(e.getPlayer().getUniqueId())) return;
        if (System.currentTimeMillis() - pb.time() > ttl()) return;
        if (pb.won() > 0) {
            jobs.addPending(pb.owner(), -pb.won());
            jobs.addDaily(pb.owner(), -pb.won());
        }
        jobs.removeXp(pb.owner(), Job.BUILDER, pb.xp());
    }

    // ---------- 건축가 ----------
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block pb = e.getBlockPlaced();
        Material m = pb.getType();
        if (isMarkable(m)) marks.put(key(pb), System.currentTimeMillis());

        if (p.getGameMode() == GameMode.CREATIVE) return;
        Job job = jobs.getJob(p.getUniqueId());
        if (job == null) return;
        if (!m.isSolid()) return;

        if (job != Job.BUILDER) {
            float add = (float) cfg().getDouble("penalty.build-exhaustion", 0.5);
            p.setExhaustion(p.getExhaustion() + add);
            return;
        }

        double diamonds = blockDiamonds(m);
        if (diamonds <= 0) return;

        UUID id = p.getUniqueId();
        String k = key(pb);
        long now = System.currentTimeMillis();
        PaidBlock prev = paid.get(k);
        if (prev != null && now - prev.time() < ttl()) return;

        double won = 0;
        if (cfg().getBoolean("economy.enabled", true)) {
            double perDiamond = cfg().getDouble("economy.diamond-price", 25000)
                    / Math.max(1.0, cfg().getDouble("economy.diamond-count", 48));
            won = diamonds * perDiamond * jobs.scale(id, Job.BUILDER);
            double left = cfg().getDouble("economy.daily-cap", 30000) - jobs.getDaily(id);
            if (left <= 0 && capShown.add(id)) {
                bar(p, "[건축가] 오늘의 건축 수익 한도에 도달했습니다.", NamedTextColor.YELLOW);
            }
            won = Math.max(0, Math.min(won, left));
            if (won > 0) {
                jobs.addPending(id, won);
                jobs.addDaily(id, won);
            }
        }

        int xp = cfg().getInt("xp.builder-place", 1);
        gainXp(p, Job.BUILDER, xp);
        paid.put(k, new PaidBlock(id, won, xp, now));
    }

    // ---------- 어부 ----------
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        Job job = jobs.getJob(p.getUniqueId());
        if (job == null) return;
        if (!(e.getCaught() instanceof Item caught)) return;

        if (job == Job.FISHER) {
            double sc = jobs.scale(p.getUniqueId(), job);
            if (chance(Math.min(0.95, cfg().getDouble("merit.fisher-bonus-chance", 0.35) * sc))) {
                ItemStack extra = caught.getItemStack().clone();
                p.getInventory().addItem(extra).values()
                        .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                int exp = (int) Math.round(cfg().getInt("merit.fisher-bonus-exp", 3) * sc);
                e.setExpToDrop(e.getExpToDrop() + exp);
                bar(p, "[어부] 대어!", NamedTextColor.GREEN);
            }
            gainXp(p, job, cfg().getInt("xp.fisher-catch", 6));
        } else if (chance(cfg().getDouble("penalty.fisher-loss-chance", 0.4))) {
            caught.remove();
            e.setExpToDrop(0);
            bar(p, "어부의 일이라 서툴러서 놓쳤습니다.", NamedTextColor.RED);
        }
    }

    // ---------- 사냥꾼 : 공격력 ----------
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player p = null;
        if (e.getDamager() instanceof Player pl) {
            p = pl;
        } else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) {
            p = pl;
        }
        if (p == null) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (victim instanceof Player || victim instanceof ArmorStand) return;

        Job job = jobs.getJob(p.getUniqueId());
        if (job == null) return;

        double mult;
        if (job == Job.HUNTER) {
            double base = cfg().getDouble("merit.hunter-damage-multiplier", 1.25);
            mult = 1.0 + (base - 1.0) * jobs.scale(p.getUniqueId(), job);
        } else {
            mult = cfg().getDouble("penalty.combat-damage-multiplier", 0.75);
        }
        e.setDamage(e.getDamage() * mult);
    }

    // ---------- 사냥꾼 : 드롭 / 경험치 ----------
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim instanceof Player) return;
        Player killer = victim.getKiller();
        if (killer == null) return;
        if (jobs.getJob(killer.getUniqueId()) != Job.HUNTER) return;
        // 스포너 몹은 보상 없음 (몹 농장 방지)
        if (victim.getPersistentDataContainer().has(spawnerKey, PersistentDataType.BYTE)) return;

        double sc = jobs.scale(killer.getUniqueId(), Job.HUNTER);
        if (chance(Math.min(0.95, cfg().getDouble("merit.hunter-drop-bonus-chance", 0.4) * sc))) {
            List<ItemStack> extra = new ArrayList<>();
            for (ItemStack d : e.getDrops()) {
                if (d.getType().getMaxDurability() == 0) extra.add(d.clone());
            }
            if (!extra.isEmpty()) {
                e.getDrops().addAll(extra);
                bar(killer, "[사냥꾼] 추가 드롭!", NamedTextColor.GREEN);
            }
        }
        gainXp(killer, Job.HUNTER, cfg().getInt("xp.hunter-kill", 4));
    }
}
