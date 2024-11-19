package nc;

import org.bukkit.plugin.java.JavaPlugin;

public final class SoulLeash extends JavaPlugin {

    public static SoulLeash instance;

    @Override
    public void onEnable() {
        // 初始化实例
        instance = this;

        // 保存默认配置文件
        saveDefaultConfig();

        // 注册监听器和命令
        getServer().getPluginManager().registerEvents(new Listeners(), this);
        getCommand("leashplayers").setExecutor(new Executors());
        getCommand("leashplayers").setTabCompleter(new Executors());

        getLogger().info("LeashPlayers 插件已成功加载！");
    }

    @Override
    public void onDisable() {
        getLogger().info("LeashPlayers 插件已关闭！");
    }
}
