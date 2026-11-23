package com.example.examplemod.compat.immptl;

import com.example.examplemod.ExampleMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;

public final class DebugFlightPortalRegistry {
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ExampleMod.MODID);

    public static final RegistryObject<EntityType<DebugFlightPortal>> DEBUG_FLIGHT_PORTAL =
            ENTITY_TYPES.register("debug_flight_portal", () ->
                    EntityType.Builder.<DebugFlightPortal>of(DebugFlightPortal::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .fireImmune()
                            .clientTrackingRange(96)
                            .build(ExampleMod.MODID + ":debug_flight_portal"));

    private DebugFlightPortalRegistry() {
    }

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }

    @Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(DEBUG_FLIGHT_PORTAL.get(), PortalEntityRenderer::new);
        }
    }
}
