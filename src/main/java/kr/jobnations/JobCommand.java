package kr.jobnations;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class JobCommand implements TabExecutor {
    private final JobNationsPlugin plugin;
    private final JobManager jobs;
    private long resetAllAskedAt = 0L;
    private boolean resetAllAskedLevels = false;

    public JobCommand(JobNationsPlugin plugin, JobManager jobs) {
        this.plugin = plugin;
        this.jobs = jobs;
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    private UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID id = jobs.findByName(name);
        if (id != null) return id;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op != null ? op.getUniqueId() : null;
    }

    private boolean denyIfNotAdmin(CommandSender s) {
        if (s.hasPermission("jobnations.admin")) return false;
        msg(s, "권한이 없습니다.", NamedTextColor.RED);
        return true;
    }

    private void showInfo(Player p) {
        UUID id = p.getUniqueId();
        Job cur = jobs.getJob(id);
        if (cur == null) {
            msg(p, "아직 직업이 없습니다. /직업 목록 으로 확인하세요.", NamedTextColor.YELLOW);
            return;
        }
        int lv = jobs.getLevel(id, cur);
        msg(p, "현재 직업: " + cur.display() + "  Lv." + lv, NamedTextColor.GREEN);
        if (lv >= jobs.maxLevel()) {
            msg(p, "경험치: 최고 레벨 달성!", NamedTextColor.GOLD);
        } else {
            int xp = jobs.getXp(id, cur);
            int need = jobs.xpNeeded(lv);
            msg(p, "경험치: " + xp + " / " + need + " (" + (xp * 100 / need) + "%)", NamedTextColor.GOLD);
        }
        double scale = jobs.scale(id, cur);
        msg(p, "메리트 배율: x" + String.format("%.2f", scale), NamedTextColor.AQUA);
        msg(p, "메리트: " + cur.merit(), NamedTextColor.AQUA);
        long rem = jobs.remainingMillis(id);
        if (rem > 0) msg(p, "변경 가능까지: " + formatTime(rem), NamedTextColor.GRAY);
        else msg(p, "지금 직업을 변경할 수 있습니다.", NamedTextColor.GRAY);
        if (cur == Job.BUILDER) {
            double cap = plugin.getConfig().getDouble("economy.daily-cap", 30000);
            msg(p, "오늘 건축 수익: " + (long) jobs.getDaily(id) + " / " + (long) cap + "원", NamedTextColor.YELLOW);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equals("정보") || args[0].equalsIgnoreCase("info")) {
            if (sender instanceof Player p) showInfo(p);
            else msg(sender, "사용법: /직업 <목록|정보|직업명>", NamedTextColor.YELLOW);
            return true;
        }

        String sub = args[0];

        if (sub.equals("목록") || sub.equalsIgnoreCase("list")) {
            for (Job j : Job.values()) {
                msg(sender, "[" + j.display() + "] +" + j.merit() + " / -" + j.demerit(), NamedTextColor.WHITE);
            }
            return true;
        }

        if (sub.equalsIgnoreCase("reload")) {
            if (denyIfNotAdmin(sender)) return true;
            plugin.reloadConfig();
            jobs.load();
            msg(sender, "설정을 다시 불러왔습니다.", NamedTextColor.GREEN);
            return true;
        }

        // ----- 관리자: 개인 초기화
        if (sub.equalsIgnoreCase("reset") || sub.equals("초기화")) {
            if (denyIfNotAdmin(sender)) return true;
            if (args.length < 2) {
                msg(sender, "사용법: /직업 초기화 <닉네임> [전체]", NamedTextColor.YELLOW);
                msg(sender, "  (기본: 직업+쿨타임만 초기화 / 전체: 레벨·경험치까지 초기화)", NamedTextColor.GRAY);
                return true;
            }
            UUID id = resolve(args[1]);
            if (id == null) {
                msg(sender, args[1] + " 님을 찾을 수 없습니다.", NamedTextColor.RED);
                return true;
            }
            boolean all = args.length >= 3 && (args[2].equals("전체") || args[2].equalsIgnoreCase("all"));
            if (all) jobs.clearAll(id);
            else jobs.clearJob(id);
            msg(sender, args[1] + " 님의 " + (all ? "직업·레벨" : "직업") + "을 초기화했습니다.", NamedTextColor.GREEN);
            Player t = Bukkit.getPlayer(id);
            if (t != null) msg(t, "직업이 초기화되었습니다. 다시 선택할 수 있습니다.", NamedTextColor.YELLOW);
            return true;
        }

        // ----- 관리자: 전체 초기화
        if (sub.equalsIgnoreCase("resetall") || sub.equals("전체초기화")) {
            if (denyIfNotAdmin(sender)) return true;
            boolean all = false, confirm = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("전체") || args[i].equalsIgnoreCase("all")) all = true;
                if (args[i].equals("확인") || args[i].equalsIgnoreCase("confirm")) confirm = true;
            }
            long now = System.currentTimeMillis();
            if (!confirm || now - resetAllAskedAt > 15_000L || resetAllAskedLevels != all) {
                resetAllAskedAt = now;
                resetAllAskedLevels = all;
                msg(sender, "⚠ 모든 플레이어의 " + (all ? "직업·레벨" : "직업") + "이 초기화됩니다.", NamedTextColor.RED);
                msg(sender, "15초 안에 /직업 전체초기화 " + (all ? "전체 " : "") + "확인 을 입력하세요.", NamedTextColor.YELLOW);
                return true;
            }
            resetAllAskedAt = 0L;
            int n = jobs.resetEveryone(all);
            msg(sender, "총 " + n + "명의 " + (all ? "직업·레벨" : "직업") + "을 초기화했습니다.", NamedTextColor.GREEN);
            for (Player t : Bukkit.getOnlinePlayers()) {
                msg(t, "관리자가 직업을 초기화했습니다. 다시 선택해 주세요.", NamedTextColor.YELLOW);
            }
            return true;
        }

        Job job = Job.fromInput(sub);
        if (job == null) {
            msg(sender, "알 수 없는 직업입니다. /직업 목록 을 확인하세요.", NamedTextColor.RED);
            return true;
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "플레이어만 직업을 선택할 수 있습니다.", NamedTextColor.RED);
            return true;
        }

        Job cur = jobs.getJob(p.getUniqueId());
        if (cur == job) {
            msg(sender, "이미 " + job.display() + "입니다.", NamedTextColor.YELLOW);
            return true;
        }
        if (cur != null) {
            long rem = jobs.remainingMillis(p.getUniqueId());
            if (rem > 0) {
                msg(sender, "직업 변경까지 " + formatTime(rem) + " 남았습니다.", NamedTextColor.RED);
                return true;
            }
        }

        jobs.setJob(p.getUniqueId(), job);
        jobs.touchName(p.getUniqueId(), p.getName());
        msg(sender, "직업이 [" + job.display() + "](으)로 설정되었습니다. (Lv." + jobs.getLevel(p.getUniqueId(), job) + ")", NamedTextColor.GREEN);
        msg(sender, "메리트: " + job.merit(), NamedTextColor.AQUA);
        return true;
    }

    private static String formatTime(long ms) {
        long totalMin = ms / 60_000L;
        long days = totalMin / 1440;
        long hours = (totalMin % 1440) / 60;
        long mins = totalMin % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("일 ");
        if (hours > 0 || days > 0) sb.append(hours).append("시간 ");
        sb.append(mins).append("분");
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("jobnations.admin");
        if (args.length == 1) {
            for (Job j : Job.values()) out.add(j.display());
            out.add("목록");
            out.add("정보");
            if (admin) {
                out.add("초기화");
                out.add("전체초기화");
                out.add("reload");
            }
        } else if (args.length == 2 && admin && (args[0].equals("초기화") || args[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length >= 2 && admin && (args[0].equals("전체초기화") || args[0].equalsIgnoreCase("resetall"))) {
            out.add("전체");
            out.add("확인");
        } else if (args.length == 3 && admin && (args[0].equals("초기화") || args[0].equalsIgnoreCase("reset"))) {
            out.add("전체");
        }
        String prefix = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(prefix));
        return out;
    }
}
