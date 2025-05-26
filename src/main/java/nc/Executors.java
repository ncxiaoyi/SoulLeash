package nc;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 指令执行类，用于处理 /soulleash 等主命令
 */
public class Executors implements CommandExecutor, TabCompleter {

    private SoulLeash main;

    // 构造方法，接受 SoulLeash 实例
    public Executors(SoulLeash main) {
        this.main = main;
    }
    /**
     * 处理命令逻辑
     * @param sender 命令发送者
     * @param command 命令对象
     * @param label 命令标签（如 "soulleash"）
     * @param args 命令参数（如 ["reload"]）
     * @return true 表示成功执行命令，false 表示失败或未识别
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 如果没有参数，返回 false 显示帮助提示
        if (args.length == 0) return false;

        // 判断是否为 reload 子命令
        if (args[0].equalsIgnoreCase("reload")) {
            main.reloadConfig(); // 重新加载 config.yml
            sender.sendMessage("§a配置文件已重新加载！");
            return true;
        }

        // 如果命令未被识别
        return false;
    }

    /**
     * 自动补全命令参数
     * @param sender 命令发送者
     * @param command 命令对象
     * @param label 命令标签
     * @param args 当前输入的参数
     * @return 补全建议列表
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        final List<String> completions = new ArrayList<>();

        // 如果正在输入第一个参数，提供建议
        if (args.length == 1) {
            List<String> COMMANDS = Collections.singletonList("reload"); // 可扩展
            // 匹配当前输入的前缀，生成补全选项
            StringUtil.copyPartialMatches(args[0], COMMANDS, completions);
            Collections.sort(completions);
        }

        return completions;
    }
}
