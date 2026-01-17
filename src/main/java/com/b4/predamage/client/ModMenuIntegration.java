package com.b4.predamage.client;
import com.b4.predamage.ModConfig;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                return ModConfig.createGui(parent);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        };
    }
}