package com.b4.predamage;

import net.fabricmc.api.ModInitializer;

public class B4PreDamage implements ModInitializer {

    @Override
    public void onInitialize() {
        ModConfig.load();
        System.out.println("B4 Main Initialized!");
    }
}
