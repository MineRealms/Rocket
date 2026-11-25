net.minecraft.server.MinecraftServer s=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
net.minecraft.server.level.ServerPlayer p=s.getPlayerList().getPlayers().get(0);
if(!p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD))throw new IllegalStateException("Start in Overworld test save");
java.lang.reflect.Method validate=com.example.examplemod.Config.class.getDeclaredMethod("validateItemName",Object.class);
validate.setAccessible(true);
for(String value:new String[]{"Not a valid ID",":", "missing:not_registered"}) {
    if((boolean)validate.invoke(null,value))throw new IllegalStateException("Invalid configured item accepted: "+value);
}
if(com.example.examplemod.Config.items.stream().anyMatch(java.util.Objects::isNull))throw new IllegalStateException("Null configured item");
java.lang.reflect.Method budget=com.example.examplemod.network.FlightNetwork.class.getDeclaredMethod("allowReply",net.minecraft.network.Connection.class);
budget.setAccessible(true);
net.minecraft.network.Connection testConnection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
int allowed=0;
for(int i=0;i<100;i++)if((boolean)budget.invoke(null,testConnection))allowed++;
if(allowed!=16)throw new IllegalStateException("Unexpected ingress burst allowance: "+allowed);
if(com.example.examplemod.flight.FlightMath.smoothRange(Double.NaN,0,1)!=0
    || com.example.examplemod.flight.FlightMath.smoothRange(1,1,0)!=0)throw new IllegalStateException("Invalid visual range not safe");
java.lang.reflect.Field landings=com.example.examplemod.compat.immptl.ImmersivePortalsBackend.class.getDeclaredField("RETURN_LANDINGS");
landings.setAccessible(true);
java.util.Map<java.util.UUID,Long> protections=(java.util.Map<java.util.UUID,Long>)landings.get(null);
protections.put(p.getUUID(),(long)s.getTickCount()+1200L);
net.minecraftforge.event.entity.living.LivingFallEvent zeroFall=new net.minecraftforge.event.entity.living.LivingFallEvent(p,0,1);
com.example.examplemod.compat.immptl.ImmersivePortalsBackend.protectReturnLanding(zeroFall);
if(!protections.containsKey(p.getUUID()))throw new IllegalStateException("Zero-distance fall consumed landing protection");
net.minecraftforge.event.entity.living.LivingFallEvent landing=new net.minecraftforge.event.entity.living.LivingFallEvent(p,20,1);
com.example.examplemod.compat.immptl.ImmersivePortalsBackend.protectReturnLanding(landing);
if(protections.containsKey(p.getUUID()) || landing.getDamageMultiplier()!=0)throw new IllegalStateException("First landing was not protected");
com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false); p.getAbilities().flying=false; p.onUpdateAbilities(); p.setOnGround(false);
p.setPos(p.getX(),com.example.examplemod.Config.flightTransitionAltitude-6,p.getZ());
p.startFallFlying(); p.setDeltaMovement(new net.minecraft.world.phys.Vec3(0,1,0));
com.example.examplemod.transition.FlightTransferCoordinator.tick(s);
try {
    java.lang.reflect.Field sessions=com.example.examplemod.transition.FlightTransferCoordinator.class.getDeclaredField("SESSIONS");
    sessions.setAccessible(true);
    Object session=((java.util.Map<?,?>)sessions.get(null)).get(p.getUUID());
    if(session==null)throw new IllegalStateException("No test preparation");
    java.lang.reflect.Field id=session.getClass().getDeclaredField("id"); id.setAccessible(true);
    java.lang.reflect.Field sequence=session.getClass().getDeclaredField("lastReplySequence"); sequence.setAccessible(true);
    java.lang.reflect.Field revision=com.example.examplemod.transition.FlightTransferCoordinator.class.getDeclaredField("revision");revision.setAccessible(true);
    java.util.UUID sessionId=(java.util.UUID)id.get(session); long rev=revision.getLong(null);
    com.example.examplemod.transition.FlightTransferCoordinator.onReply(p,new com.example.examplemod.network.FlightNetwork.ClientReply(sessionId,Long.MAX_VALUE,rev,true,false,"overflow injection"));
    if(sequence.getLong(session)!=0)throw new IllegalStateException("Overflow reply poisoned session");
    com.example.examplemod.transition.FlightTransferCoordinator.onReply(p,new com.example.examplemod.network.FlightNetwork.ClientReply(sessionId,1,rev,false,false,"valid after overflow"));
    if(sequence.getLong(session)!=1)throw new IllegalStateException("Valid reply rejected after overflow");
    com.example.examplemod.transition.FlightTransferCoordinator.onReply(p,new com.example.examplemod.network.FlightNetwork.ClientReply(sessionId,3,rev,false,false,"after one network-limited reply"));
    if(sequence.getLong(session)!=3)throw new IllegalStateException("Reply sequence gap stalled the session");
    com.example.examplemod.transition.FlightTransferCoordinator.onReply(p,new com.example.examplemod.network.FlightNetwork.ClientReply(sessionId,2,rev,true,false,"stale reply"));
    if(sequence.getLong(session)!=3)throw new IllegalStateException("Out-of-order reply rolled sequence back");
    long before=rev;
    com.example.examplemod.Config.loadRevision++;
    com.example.examplemod.transition.FlightTransferCoordinator.tick(s);
    if(revision.getLong(null)<=before)throw new IllegalStateException("Same-value config reload not synchronized");
    return "PASS: invalid IDs/nulls, invalid render ranges, zero-distance/first-landing protection, bounded reply sequence, skipped-rate-limited sequence, stale reply rejection, same-value config reload";
} finally {
    com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
    p.stopFallFlying(); p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities();
    p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
}
