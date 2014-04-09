package net.amoebaman.combatmods;

import java.io.File;
import java.util.HashMap;

import net.amoebaman.statmaster.StatMaster;
import net.amoebaman.statmaster.Statistic;
import net.amoebaman.utils.plugin.MetricsLite;
import net.amoebaman.utils.plugin.Updater;
import net.amoebaman.utils.plugin.Updater.UpdateType;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public class CombatMods extends JavaPlugin implements Listener{
	
	private File configFile;
	private ConfigurationSection parrying, headshots, airborne, lunging, armoredBoats, fastArrows, elevatedArchery, arrowRetrieval, antispamBows, brokenKnees, assassinations, noDurability, potionLobber;
	private boolean statTracking;
	private int airborneTaskId = -1;
	
	private static final HashMap<String, Long> lastParryAttempt = new HashMap<String, Long>();
	private static final HashMap<LivingEntity, Integer> arrowsLodged = new HashMap<LivingEntity, Integer>();
	private HashMap<String, Long> lastBowDraw = new HashMap<String, Long>();
	
	public void onEnable(){
		Bukkit.getPluginManager().registerEvents(this, this);
		
		getDataFolder().mkdirs();
		configFile = new File(getDataFolder().getPath() + "/config.yml");
		try{ loadConfig(); }
		catch(Exception e){ e.printStackTrace(); }
		
		try { new MetricsLite(this).start(); }
		catch (Exception e) { e.printStackTrace(); }
		
		Updater.checkConfig();
		if(Updater.isEnabled())
			new Updater(this, 42663, getFile(), UpdateType.DEFAULT, true);
	}
	
	public void onDisable(){
		Bukkit.getScheduler().cancelTask(airborneTaskId);
	}
	
	public void loadConfig() throws Exception{
		try{
			if(!configFile.exists()){
				configFile.createNewFile();
			}
			getConfig().options().copyDefaults(true);
			getConfig().load(configFile);
			getConfig().save(configFile);
			parrying = getConfig().getConfigurationSection("parrying");
			headshots = getConfig().getConfigurationSection("headshots");
			airborne = getConfig().getConfigurationSection("airborne");
			lunging = getConfig().getConfigurationSection("lunging");
			armoredBoats= getConfig().getConfigurationSection("armored-boats");
			fastArrows = getConfig().getConfigurationSection("fast-arrows");
			elevatedArchery = getConfig().getConfigurationSection("elevated-archery");
			arrowRetrieval = getConfig().getConfigurationSection("arrow-retrieval");
			antispamBows = getConfig().getConfigurationSection("antispam-bows");
			brokenKnees = getConfig().getConfigurationSection("broken-knees");
			assassinations = getConfig().getConfigurationSection("assassinations");
			noDurability = getConfig().getConfigurationSection("no-durability");
			potionLobber = getConfig().getConfigurationSection("potion-lobber");
		}
		catch(Exception e){
			e.printStackTrace();
			throw new Exception("something went horribly wrong while loading the config");
		}
		
		Bukkit.getScheduler().cancelTask(airborneTaskId);
		if(airborne.getBoolean("enabled", false))
			airborneTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new AirborneRegulator(), 0L, 1L);
		
		statTracking = Bukkit.getPluginManager().getPlugin("StatMaster") != null;
		if(statTracking){
			StatMaster.getHandler().registerStat(new Statistic("Life saving parries", 0, "combat"));
			StatMaster.getHandler().registerStat(new Statistic("Headshots", 0, "combat"));
			StatMaster.getHandler().registerStat(new Statistic("Assassinations", 0, "combat"));
		}
		
		for(String component : getConfig().getKeys(false))
			getLogger().info(component + " " + (getConfig().getConfigurationSection(component).getBoolean("enabled") ? "enabled" : "disabled"));
	}
	
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
		if(command.getName().equals("cmreload")){
			try{
				loadConfig();
				sender.sendMessage(ChatColor.GREEN + "CombatMods config reloaded");
			}
			catch(Exception e){
				sender.sendMessage(ChatColor.RED + "Error reloading config");
				return true;
			}
		}
		return true;
	}
	
	@EventHandler
	public void detectParrying(PlayerInteractEvent event){
		if(!parrying.getBoolean("enabled"))
			return;
		final Player player = event.getPlayer();
		if(player.getItemInHand() == null)
			return;
		final ItemStack inHand = player.getItemInHand();
		if(inHand.getType().name().contains("SWORD") && event.getAction().name().contains("RIGHT_CLICK")){
			if(!lastParryAttempt.containsKey(player.getName()))
				lastParryAttempt.put(player.getName(), 0L);
			if(System.currentTimeMillis() - lastParryAttempt.get(player.getName()) < parrying.getInt("fumble-time") && Math.random() < parrying.getDouble("fumble-chance"))
				Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable(){ public void run(){
					player.getWorld().dropItemNaturally(player.getLocation(), inHand).setPickupDelay(20);
					player.getInventory().remove(inHand);
					player.sendMessage(ChatColor.translateAlternateColorCodes('&', parrying.getString("fumble-message")));
				}});
			else
				lastParryAttempt.put(player.getName(), System.currentTimeMillis());
		}
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void skillfulParrying(EntityDamageEvent event){
		if(!parrying.getBoolean("enabled"))
			return;
		if(!(event.getEntity() instanceof Player))
			return;
		final Player player = (Player) event.getEntity();
		if(player.isBlocking() && player.getNoDamageTicks() == 0 && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.PROJECTILE)){
			if(!lastParryAttempt.containsKey(player.getName()))
				lastParryAttempt.put(player.getName(), 0L);
			if(System.currentTimeMillis() - lastParryAttempt.get(player.getName()) < parrying.getInt("parry-time") && Math.random() < parrying.getDouble("parry-chance")){
				event.setCancelled(true);
				player.getWorld().playEffect(player.getLocation(), Effect.ZOMBIE_CHEW_IRON_DOOR, 0);
				player.sendMessage(ChatColor.translateAlternateColorCodes('&', parrying.getString("parry-message")));
				if(event.getDamage() > player.getHealth() && statTracking)
					StatMaster.getHandler().incrementStat(player, "life saving parries");
			}
			else if(System.currentTimeMillis() - lastParryAttempt.get(player.getName()) > parrying.getInt("disarm-time") && Math.random() < parrying.getDouble("disarm-chance"))
				Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable(){ public void run(){
					player.getWorld().dropItemNaturally(player.getLocation(), player.getItemInHand());
					player.getInventory().remove(player.getItemInHand());
					player.sendMessage(ChatColor.translateAlternateColorCodes('&', parrying.getString("disarm-message")));
				}});
		}
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void headshotBonus(EntityDamageEvent event){
		if(!headshots.getBoolean("enabled"))
			return;
		if(!(event.getEntity() instanceof Player) || event.isCancelled())
			return;
		Player player = (Player) event.getEntity();		
		if(!(event instanceof EntityDamageByEntityEvent))
			return;
		EntityDamageByEntityEvent eEvent = (EntityDamageByEntityEvent) event;
		if(eEvent.getDamager() instanceof Arrow){
			Projectile proj = (Projectile) eEvent.getDamager();
			if(proj.getLocation().distance(player.getEyeLocation()) <= 1.2 && proj.getShooter() instanceof Player){
				event.setDamage((int) (event.getDamage() * headshots.getDouble("multiplier")));
				((Player) proj.getShooter()).sendMessage(ChatColor.translateAlternateColorCodes('&', headshots.getString("dealt-message").replace("%victim%", player.getName())));
				player.sendMessage(ChatColor.translateAlternateColorCodes('&', headshots.getString("taken-message").replace("%player%", (((Player) proj.getShooter()).getName()))));
				if(statTracking)
					StatMaster.getHandler().incrementStat((Player) proj.getShooter(), "headshots"); 
			}
		}
	}
	
	@EventHandler
	public void lunging(PlayerMoveEvent event){
		if(!lunging.getBoolean("enabled"))
			return;
		Player player = event.getPlayer();
		if(player.isSprinting() && event.getFrom().getY() < event.getTo().getY() && event.getTo().getY() - event.getFrom().getY() != 0.5){
			player.setSprinting(false);
			player.sendMessage(ChatColor.translateAlternateColorCodes('&', lunging.getString("message")));
		}
	}
	
	@EventHandler
	public void armoredBoats(VehicleDestroyEvent event){
		if(!armoredBoats.getBoolean("enabled"))
			return;
		if(event.getVehicle() instanceof Boat && event.getAttacker() != null && !event.getAttacker().equals(event.getVehicle().getPassenger()))
			event.setCancelled(true);
	}
	
	@EventHandler
	public void cleanDeadBoats(PlayerDeathEvent event){
		if(!armoredBoats.getBoolean("enabled"))
			return;
		if(event.getEntity().getVehicle() instanceof Boat && armoredBoats.getBoolean("prevent-boat-litter"))
			event.getEntity().getVehicle().remove();
	}
	
	@EventHandler
	public void cleanVacatedBoats(VehicleExitEvent event){
		if(!armoredBoats.getBoolean("enabled"))
			return;
		if(event.getVehicle() instanceof Boat && armoredBoats.getBoolean("prevent-boat-litter"))
			event.getVehicle().remove();
	}
	
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void tagArrows(ProjectileLaunchEvent event){
		Projectile proj = event.getEntity();
		if(proj instanceof Arrow && proj.getShooter() instanceof Player){
			proj.setMetadata("launch-vel", new FixedMetadataValue(this, proj.getVelocity()));
			proj.setMetadata("launch-pos", new FixedMetadataValue(this, proj.getLocation()));
		}
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void fastArrows(ProjectileLaunchEvent event){
		if(!fastArrows.getBoolean("enabled"))
			return;
		if(event.getEntity() instanceof Arrow){
			Arrow arrow = (Arrow) event.getEntity();
			arrow.setVelocity(arrow.getVelocity().multiply(fastArrows.getDouble("speed")));
		}
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void fastArrowNerf(EntityDamageByEntityEvent event){
		if(!(fastArrows.getBoolean("enabled") && fastArrows.getBoolean("disable-damage-increase")))
			return;
		if(event.getDamager() instanceof Arrow && event.getDamager().hasMetadata("launch-vel"))
			event.setDamage((int) (event.getDamage() / fastArrows.getDouble("speed")));
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void elevatedArchery(EntityDamageByEntityEvent event){
		if(!elevatedArchery.getBoolean("enabled", true))
			return;
		if(event.getDamager() instanceof Arrow && event.getDamager().hasMetadata("launch-pos")){
			Vector start = ((Location) event.getDamager().getMetadata("launch-pos").get(0).value()).toVector();
			Vector finish = event.getDamager().getLocation().toVector();
			Vector diff = finish.clone().subtract(start);
			double ydiff = -diff.getY();
			double xzdiff = Math.sqrt(Math.pow(diff.getX(), 2) + Math.pow(diff.getZ(), 2));
			double mult = 1 + elevatedArchery.getDouble("max-extra", 0.75) * Math.sin( Math.atan(ydiff / xzdiff) );
			if(mult < 0)
				mult = 0;
			event.setDamage(event.getDamage() * mult);
		}
	}
	
	@EventHandler
	public void logArrowHits(EntityDamageByEntityEvent event){
		if(!arrowRetrieval.getBoolean("enabled"))
			return;
		if(!(event.getDamager() instanceof Arrow && event.getEntity() instanceof LivingEntity))
			return;
		Arrow arrow = (Arrow) event.getDamager();
		LivingEntity victim = (LivingEntity) event.getEntity();
		if(!(arrow.getShooter() instanceof Player))
			return;
		Player shooter = (Player) arrow.getShooter();
		if(!shooter.getItemInHand().getEnchantments().containsKey(Enchantment.ARROW_INFINITE)){
			if(!arrowsLodged.containsKey(victim))
				arrowsLodged.put(victim, 0);
			if(Math.random() < arrowRetrieval.getDouble("retrieval-rate"))
				arrowsLodged.put(victim, arrowsLodged.get(victim) + 1);
		}
	}
	
	@EventHandler
	public void arrowRetrieval(EntityDeathEvent event){
		if(!arrowRetrieval.getBoolean("enabled"))
			return;
		if(!(event.getEntity() instanceof LivingEntity))
			return;
		LivingEntity victim = (LivingEntity) event.getEntity();
		if(arrowsLodged.containsKey(victim))
			victim.getWorld().dropItemNaturally(victim.getLocation(), new ItemStack(Material.ARROW, arrowsLodged.get(victim)));
		arrowsLodged.remove(victim);
	}
	
	@EventHandler
	public void detectBowDraw(PlayerInteractEvent event){
		if(!antispamBows.getBoolean("enabled"))
			return;
		final Player player = event.getPlayer();
		if(player.getItemInHand() == null)
			return;
		Material inHand = player.getItemInHand().getType();
		if(inHand == Material.BOW && event.getAction().name().contains("RIGHT_CLICK"))
			lastBowDraw.put(player.getName(), System.currentTimeMillis());
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void antispamBows(ProjectileLaunchEvent event){
		if(!antispamBows.getBoolean("enabled"))
			return;
		Player shooter = null;
		if(event.getEntity().getShooter() instanceof Player){
			shooter = (Player) event.getEntity().getShooter();
			if(!lastBowDraw.containsKey(shooter.getName()))
				lastBowDraw.put(shooter.getName(), 0L);
			
			if(System.currentTimeMillis() - lastBowDraw.get(shooter.getName()) < antispamBows.getInt("minimum-draw")){
				event.setCancelled(true);
				if(!shooter.getItemInHand().containsEnchantment(Enchantment.ARROW_INFINITE) && shooter.getGameMode() != GameMode.CREATIVE)
					shooter.getWorld().dropItemNaturally(shooter.getLocation(), new ItemStack(Material.ARROW, 1));
				shooter.sendMessage(ChatColor.translateAlternateColorCodes('&', antispamBows.getString("message")));
			}
		}
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void brokenKnees(EntityDamageEvent event){
		if(!brokenKnees.getBoolean("enabled"))
			return;
		Player player = null;
		if(event.getEntity() instanceof Player)
			player = (Player) event.getEntity();
		boolean featherFalling = player != null && player.getInventory().getBoots() != null && player.getInventory().getBoots().containsEnchantment(Enchantment.PROTECTION_FALL);
		if(brokenKnees.getBoolean("feather-falling") && featherFalling)
			return;
		if(event.getCause() == DamageCause.FALL)
			event.setDamage((int) (event.getDamage() * brokenKnees.getDouble("fall-multiplier")));
	}
	
	@EventHandler
	public void assassinations(PlayerInteractEntityEvent event){
		if(!assassinations.getBoolean("enabled"))
			return;
		if(!(event.getRightClicked() instanceof Player))
			return;
		final Player player = event.getPlayer();
		final Player victim  = (Player) event.getRightClicked();
		/*
		 * Make sure the player is at the right range, and that their rotation is comparable
		 * This is how we check to make sure they're behind their target
		 * 
		 * Also make sure there isn't already an assassination in progress
		 */
		double yawDiff = Math.abs(getYaw(player) - getYaw(victim));
		double distance = player.getLocation().distance(victim.getLocation());
		if(distance < 2 && distance > 0.5 && (yawDiff < 45 || yawDiff > 315)){
			/*
			 * Call a tester event to make sure the damage is actually allowed
			 */
			EntityDamageEvent testEvent = new EntityDamageByEntityEvent(player, victim, DamageCause.CUSTOM, 9001.0);
			Bukkit.getPluginManager().callEvent(testEvent);
			if(testEvent.isCancelled())
				return;
			/*
			 * Schedule the warning message
			 */
			final JavaPlugin plugin = this;
			Bukkit.getScheduler().runTaskLater(plugin, new Runnable(){ public void run(){
				
				double yawDiff = Math.abs(getYaw(player) - getYaw(victim));
				double distance = player.getLocation().distance(victim.getLocation());
				if(victim.getHealth() > 0 && player.getHealth() > 0 && distance < 2 && distance > 0.5 && (yawDiff < 45 || yawDiff > 315) && !victim.hasMetadata("assassination-marker"))
					victim.sendMessage(ChatColor.translateAlternateColorCodes('&', assassinations.getString("warning-message").replace("%player%", player.getName())));
				victim.setMetadata("assassination-marker", new FixedMetadataValue(plugin, 0));
				
			}}, 20 * assassinations.getInt("warning-time", 500) / 1000);
			/*
			 * Schedule the assassination
			 */
			Bukkit.getScheduler().runTaskLater(plugin, new Runnable(){ public void run(){
				
				double yawDiff = Math.abs(getYaw(player) - getYaw(victim));
				double distance = player.getLocation().distance(victim.getLocation());
				if(victim.getHealth() > 0 && player.getHealth() > 0 && distance < 2 && distance > 0.5 && (yawDiff < 45 || yawDiff > 315)){
					victim.setHealth(0);
					victim.setLastDamageCause(new EntityDamageByEntityEvent(player, victim, DamageCause.CUSTOM, victim.getMaxHealth()));
					player.sendMessage(ChatColor.translateAlternateColorCodes('&', assassinations.getString("dealt-message").replace("%victim%", victim.getName())));
					victim.sendMessage(ChatColor.translateAlternateColorCodes('&', assassinations.getString("taken-message").replace("%player%", player.getName())));
					if(statTracking)
						StatMaster.getHandler().incrementStat(player, "assassinations");
				}
				victim.removeMetadata("assassination-marker", plugin);
				
			}}, 20 * assassinations.getInt("death-time", 500) / 1000);
		}
	}
	
	private double getYaw(Player player){
		double yaw = player.getLocation().getYaw();
		if(yaw >= 360)
			yaw -= 360;
		if(yaw < 0)
			yaw += 360;
		return yaw;
	}
	
	@SuppressWarnings("deprecation")
	@EventHandler
	public void infiniteWeaponArmorDurability(EntityDamageEvent event){
		if(!noDurability.getBoolean("enabled"))
			return;
		if(event.getEntityType() == EntityType.PLAYER){
			final Player victim = (Player) event.getEntity();
			for(final ItemStack armor : victim.getInventory().getArmorContents())
				if(armor != null)
					Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable(){ public void run(){
						armor.setDurability((short) 0);
						victim.updateInventory();
					}});
		}
		if(event instanceof EntityDamageByEntityEvent){
			EntityDamageByEntityEvent eEvent = (EntityDamageByEntityEvent) event;
			final Player damager;
			if(eEvent.getDamager().getType() == EntityType.PLAYER)
				damager = (Player) eEvent.getDamager();
			else if(eEvent.getDamager().getType() == EntityType.ARROW){
				Arrow arrow = (Arrow) eEvent.getDamager();
				if(arrow.getShooter() instanceof Player)
					damager = (Player) arrow.getShooter();
				else
					damager = null;
			}
			else
				damager = null;
			if(damager != null){
				Material weapon = damager.getItemInHand().getType();
				if(weapon.name().contains("SWORD") || weapon.name().contains("AXE"))
					Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable(){ public void run(){
						damager.getItemInHand().setDurability((short) 0);
						damager.updateInventory();
					}});
				
			}
		}
	}
	
	@SuppressWarnings("deprecation")
	@EventHandler
	public void infiniteBowDurability(ProjectileLaunchEvent event){
		if(!noDurability.getBoolean("enabled"))
			return;
		if(event.getEntityType() == EntityType.ARROW){
			final Arrow arrow = (Arrow) event.getEntity();
			if(arrow.getShooter() != null && arrow.getShooter() instanceof Player)
				Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable(){ public void run(){
					((Player) arrow.getShooter()).getItemInHand().setDurability((short) 0);
					((Player) arrow.getShooter()).updateInventory();
				}});
		}
	}
	
	@EventHandler
	public void potionLobber(ProjectileLaunchEvent event){
		if(!potionLobber.getBoolean("enabled"))
			return;
		if(event.getEntityType() == EntityType.SPLASH_POTION)
			event.getEntity().setVelocity(event.getEntity().getVelocity().multiply(potionLobber.getDouble("multiplier")));
	}
	
}
