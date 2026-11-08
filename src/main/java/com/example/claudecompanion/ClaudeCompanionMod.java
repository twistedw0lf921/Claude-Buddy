package com.example.claudecompanion;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(ClaudeCompanionMod.MODID)
public class ClaudeCompanionMod {
    public static final String MODID = "claudecompanion";

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<ClaudeEntity>> CLAUDE = ENTITIES.register("claude",
            () -> EntityType.Builder.<ClaudeEntity>of(ClaudeEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build("claude"));

    public ClaudeCompanionMod(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        ENTITIES.register(modBus);
        modBus.addListener(this::onAttributes);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(new ForgeEvents());
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(CLAUDE.get(), ClaudeEntity.createAttributes().build());
    }
}
