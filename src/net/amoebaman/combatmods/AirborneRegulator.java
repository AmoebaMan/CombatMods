package net.amoebaman.combatmods;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class AirborneRegulator implements Runnable{
	
	private Map<UUID, Vector> lastTickVel = new HashMap<UUID, Vector>();
	private Map<UUID, Boolean> lastTickOnGround = new HashMap<UUID, Boolean>();
	
	@SuppressWarnings("deprecation")
	public void run(){
		for(World world : Bukkit.getWorlds())
			for(Player player : world.getEntitiesByClass(Player.class)){
				
				UUID uuid = player.getUniqueId();
				Vector newV = player.getVelocity().clone();
				
				if(lastTickVel.containsKey(uuid) && lastTickOnGround.containsKey(uuid) && !player.isFlying() && !player.isOnGround() && !player.isInsideVehicle()){
					
					Vector oldV = (Vector) lastTickVel.get(uuid);
					
					boolean newxchanged = Math.abs(newV.getX()) > 0.001;
					boolean oldxchanged = Math.abs(oldV.getX()) > 0.001;
					
					boolean newzchanged = Math.abs(newV.getZ()) > 0.001;
					boolean oldzchanged = Math.abs(oldV.getZ()) > 0.001;
					
					if(!lastTickOnGround.get(uuid)){
						
						if(newxchanged && oldxchanged)
							newV.setX(oldV.getX() * 0.975);
						
						if(newzchanged && oldzchanged)
							newV.setZ(oldV.getZ() * 0.975);
						
					}
					
					else{
						
						System.out.println(oldV.toString());
						System.out.println(newV.toString());
						
						if(player.isSprinting()){
							
							float modifier = player.getWalkSpeed() / 0.25f;
							
							for(PotionEffect effect : player.getActivePotionEffects()){
								if(effect.getType().equals(PotionEffectType.SPEED))
									for(int i = 0; i < effect.getAmplifier() + 1; i++)
										modifier *= 1.2f;
								if(effect.getType().equals(PotionEffectType.SLOW))
									for(int i = 0; i < effect.getAmplifier() + 1; i++)
										modifier *= 0.85f;
							}

							if(newxchanged && oldxchanged)
								newV.setX(newV.getX() * modifier);
							if(newzchanged && oldzchanged)
								newV.setZ(newV.getZ() * modifier);
							
						}
						
					}
					
					player.setVelocity(newV.clone());
				}
				
				lastTickVel.put(uuid, newV.clone());
				lastTickOnGround.put(uuid, player.isOnGround());
				
			}
	}
	
}
