package com.rheinmetal.tianshu.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ServerConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ALLOW_AUTO_EQUIP;
    public static final ModConfigSpec.BooleanValue ALLOW_AUTO_TRASH;
    public static final ModConfigSpec.BooleanValue ALLOW_HIGH_PRECISION_MODE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("天枢模组服务端权限配置").push("permissions");
        ALLOW_AUTO_EQUIP = builder.comment("是否允许客户端使用装备切换功能")
                .define("allowAutoEquip", true);
        ALLOW_AUTO_TRASH = builder.comment("是否允许客户端使用一键扔垃圾功能")
                .define("allowAutoTrash", true);
        ALLOW_HIGH_PRECISION_MODE = builder.comment("是否允许客户端使用高精度雷达")
                .define("allowHighPrecisionMode", true);
        builder.pop();

        SPEC = builder.build();
    }
}
