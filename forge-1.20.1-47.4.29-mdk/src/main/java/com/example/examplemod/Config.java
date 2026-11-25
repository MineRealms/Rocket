package com.example.examplemod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Forge's config APIs
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.DoubleValue FLIGHT_TRANSITION_ALTITUDE = BUILDER
            .comment("Overworld portal plane for debug Elytra flight to space Y=64.")
            .defineInRange("flightTransitionAltitude", 1600.0, 384.0, 2048.0);

    private static final ForgeConfigSpec.BooleanValue DEBUG_ELYTRA_SPACE_FLIGHT = BUILDER
            .comment("Development only: allow unmounted Elytra gliding through the Overworld/space boundary.",
                    "False removes the debug connection. Production flight requires a future registered rocket vehicle.")
            .define("debugElytraSpaceFlight", true);

    private static final ForgeConfigSpec.BooleanValue GENERATE_ORBITAL_TERRAIN = BUILDER
            .comment("Generate actual Overworld chunks for the persistent 32 km orbital LOD atlas.",
                    "One chunk request at a time, at most five starts per second while a player is above Y=300 or in space.")
            .define("generateOrbitalTerrain", true);

    public static final ForgeConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static volatile double flightTransitionAltitude = 1600.0;
    public static volatile boolean debugElytraSpaceFlight = true;
    public static volatile boolean generateOrbitalTerrain = true;
    public static String magicNumberIntroduction;
    public static Set<Item> items = Set.of();
    public static volatile long loadRevision;

    private static boolean validateItemName(final Object obj)
    {
        if (!(obj instanceof String itemName)) return false;
        ResourceLocation id = ResourceLocation.tryParse(itemName);
        return id != null && ForgeRegistries.ITEMS.containsKey(id) && ForgeRegistries.ITEMS.getValue(id) != null;
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        if (event.getConfig().getSpec() != SPEC || event instanceof ModConfigEvent.Unloading) return;
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        flightTransitionAltitude = FLIGHT_TRANSITION_ALTITUDE.get();
        debugElytraSpaceFlight = DEBUG_ELYTRA_SPACE_FLIGHT.get();
        generateOrbitalTerrain = GENERATE_ORBITAL_TERRAIN.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream()
                .map(ResourceLocation::tryParse)
                .filter(Objects::nonNull)
                .map(ForgeRegistries.ITEMS::getValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        loadRevision++;
    }
}
