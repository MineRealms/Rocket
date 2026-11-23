
Rocket Mod 1.20.1 — dimension transition prototype
==================================================

Environment
-----------
- Minecraft 1.20.1 / Forge 47.4.29 / Java 17.
- Gradle downloads use the local proxy at 127.0.0.1:15236. Keep that proxy
  running when building or launching the development client.
- Immersive Portals 3.0.7 and Cloth Config 11.1.136 are resolved automatically
  for development runs. For a manually installed mod, install both dependencies
  alongside rocketmod-1.0.0.jar.

Build and launch
----------------
- Run build-mod.bat to build the mod. The distributable JAR is in build/libs.
- Run run-client.bat to start the Forge development client.

Automatic altitude transition
-----------------------------
- The common config file is run/config/rocketmod-common.toml. The default
  flightTransitionAltitude is 512.0.
- Fly to actual player Y >= 512 in the Overworld to enter rocketmod:space
  automatically. The server checks at the end of each tick. No command or
  operator permission is needed for the automatic trigger. Block placement
  height does not limit how high a player can fly.
- The arrival position keeps X/Z and maps Y to 64 + (departure Y - threshold).
  Creative flight is recommended when trying the empty space dimension.
- /rocketmod flight altitude 512 still simulates crossing the threshold.
  /rocketmod flight return returns to the Overworld spawn. Both commands need
  commands enabled or operator permission.
- Future rocket flight code should call
  FlightDimensionTransition.onVirtualAltitudeChanged(pilot, virtualY) with the
  pilot and the rocket's virtual Y altitude. This shares the same threshold as
  the automatic player Y check.

Source installation information for modders
-------------------------------------------
This code follows the Minecraft Forge installation methodology. It will apply
some small patches to the vanilla MCP source code, giving you and it access 
to some of the data and functions you need to build a successful mod.

Note also that the patches are built against "un-renamed" MCP source code (aka
SRG Names) - this means that you will not be able to read them directly against
normal code.

Setup Process:
==============================

Step 1: Open your command-line and browse to the folder where you extracted the zip file.

Step 2: You're left with a choice.
If you prefer to use Eclipse:
1. Run the following command: `./gradlew genEclipseRuns`
2. Open Eclipse, Import > Existing Gradle Project > Select Folder 
   or run `gradlew eclipse` to generate the project.

If you prefer to use IntelliJ:
1. Open IDEA, and import project.
2. Select your build.gradle file and have it import.
3. Run the following command: `./gradlew genIntellijRuns`
4. Refresh the Gradle Project in IDEA if required.

If at any point you are missing libraries in your IDE, or you've run into problems you can 
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
(this does not affect your code) and then start the process again.

Mapping Names:
=============================
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license, if you do not agree with it you can change your mapping names to other crowdsourced names in your 
build.gradle. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/MinecraftForge/MCPConfig/blob/master/Mojang.md

Additional Resources: 
=========================
Community Documentation: https://docs.minecraftforge.net/en/1.20.1/gettingstarted/
LexManos' Install Video: https://youtu.be/8VEdtQLuLO0
Forge Forums: https://forums.minecraftforge.net/
Forge Discord: https://discord.minecraftforge.net/
