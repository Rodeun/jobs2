package kr.jobnations;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

public class JobManager {
    private final JobNationsPlugin plugin;
    private final File file;
    private YamlConfiguration data;
    private boolean dirty = false;

    public JobManager(JobNationsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        data = YamlConfiguration.loadConfiguration(file);
        dirty = false;
    }

    public synchronized void save() {
        try {
            data.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("data.yml 저장 실패: " + e.getMessage());
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    private String p(UUID id) {
        return "players." + id;
    }

    // ---------- 직업 ----------
    public Job getJob(UUID id) {
        String s = data.getString(p(id) + ".job");
        if (s == null) return null;
        try {
            return Job.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setJob(UUID id, Job job) {
        data.set(p(id) + ".job", job.name());
        data.set(p(id) + ".changed", System.currentTimeMillis());
        save();
    }

    private void clearJobNoSave(UUID id) {
        data.set(p(id) + ".job", null);
        data.set(p(id) + ".changed", null);
    }

    /** 직업과 쿨타임만 초기화 (레벨 유지) */
    public void clearJob(UUID id) {
        clearJobNoSave(id);
        save();
    }

    private void clearAllNoSave(UUID id) {
        double pending = getPending(id);
        String name = data.getString(p(id) + ".name");
        data.set(p(id), null);
        if (pending != 0) data.set(p(id) + ".pending", pending);
        if (name != null) data.set(p(id) + ".name", name);
    }

    /** 직업, 쿨타임, 레벨, 경험치 전부 초기화 (정산 대기 금액은 유지) */
    public void clearAll(UUID id) {
        clearAllNoSave(id);
        save();
    }

    /** 모든 플레이어 초기화. 처리한 인원 수 반환 */
    public int resetEveryone(boolean withLevels) {
        ConfigurationSection sec = data.getConfigurationSection("players");
        if (sec == null) return 0;
        int n = 0;
        for (String key : new ArrayList<>(sec.getKeys(false))) {
            try {
                UUID id = UUID.fromString(key);
                if (withLevels) clearAllNoSave(id);
                else clearJobNoSave(id);
                n++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        save();
        return n;
    }

    /** 남은 쿨타임(ms). 0이면 변경 가능 */
    public long remainingMillis(UUID id) {
        long cooldown = (long) (plugin.getConfig().getDouble("change-cooldown-hours", 48) * 3_600_000L);
        long changed = data.getLong(p(id) + ".changed", 0L);
        return Math.max(0L, changed + cooldown - System.currentTimeMillis());
    }

    // ---------- 이름 ----------
    public void touchName(UUID id, String name) {
        if (!name.equals(data.getString(p(id) + ".name"))) {
            data.set(p(id) + ".name", name);
            dirty = true;
        }
    }

    public UUID findByName(String name) {
        ConfigurationSection sec = data.getConfigurationSection("players");
        if (sec == null) return null;
        for (String key : sec.getKeys(false)) {
            String n = sec.getString(key + ".name");
            if (n != null && n.equalsIgnoreCase(name)) {
                try {
                    return UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }

    // ---------- 레벨 ----------
    public int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt("level.max-level", 20));
    }

    public int xpNeeded(int level) {
        int base = plugin.getConfig().getInt("level.xp-base", 100);
        int step = plugin.getConfig().getInt("level.xp-step", 50);
        return Math.max(1, base + step * (level - 1));
    }

    public int getLevel(UUID id, Job job) {
        return Math.max(1, data.getInt(p(id) + ".levels." + job.name() + ".level", 1));
    }

    public int getXp(UUID id, Job job) {
        return Math.max(0, data.getInt(p(id) + ".levels." + job.name() + ".xp", 0));
    }

    /** 경험치를 더하고 오른 레벨 수를 반환 */
    public int addXp(UUID id, Job job, int amount) {
        if (amount <= 0) return 0;
        int lv = getLevel(id, job);
        int max = maxLevel();
        if (lv >= max) return 0;
        long xp = getXp(id, job) + (long) amount;
        int gained = 0;
        while (lv < max && xp >= xpNeeded(lv)) {
            xp -= xpNeeded(lv);
            lv++;
            gained++;
        }
        if (lv >= max) xp = 0;
        data.set(p(id) + ".levels." + job.name() + ".level", lv);
        data.set(p(id) + ".levels." + job.name() + ".xp", (int) xp);
        dirty = true;
        return gained;
    }

    /** 경험치만 깎음 (레벨은 내려가지 않음) */
    public void removeXp(UUID id, Job job, int amount) {
        if (amount <= 0) return;
        int xp = Math.max(0, getXp(id, job) - amount);
        data.set(p(id) + ".levels." + job.name() + ".xp", xp);
        dirty = true;
    }

    /** 레벨에 따른 메리트 배율 */
    public double scale(UUID id, Job job) {
        double per = plugin.getConfig().getDouble("level.merit-scale-per-level", 0.05);
        return 1.0 + (getLevel(id, job) - 1) * per;
    }

    // ---------- 건축 수익 ----------
    public double getPending(UUID id) {
        return data.getDouble(p(id) + ".pending", 0.0);
    }

    public void addPending(UUID id, double amount) {
        data.set(p(id) + ".pending", getPending(id) + amount);
        dirty = true;
    }

    public double getDaily(UUID id) {
        String d = data.getString(p(id) + ".daily.date");
        if (!LocalDate.now().toString().equals(d)) return 0.0;
        return data.getDouble(p(id) + ".daily.amount", 0.0);
    }

    public void addDaily(UUID id, double amount) {
        String today = LocalDate.now().toString();
        double cur = getDaily(id);
        data.set(p(id) + ".daily.date", today);
        data.set(p(id) + ".daily.amount", Math.max(0.0, cur + amount));
        dirty = true;
    }
}
