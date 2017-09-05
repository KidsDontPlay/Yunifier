package mrriegel.instantunify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.oredict.OreDictionary;
import wanion.unidict.UniDict;
import wanion.unidict.resource.ResourceHandler;

@Mod(modid = InstantUnify.MODID, name = InstantUnify.NAME, version = InstantUnify.VERSION, dependencies = "before:unidict", acceptedMinecraftVersions = "[1.12]", acceptableRemoteVersions = "*")
@EventBusSubscriber
public class InstantUnify {

	@Instance(InstantUnify.MODID)
	public static InstantUnify INSTANCE;

	public static final String VERSION = "1.0.1";
	public static final String NAME = "InstantUnify";
	public static final String MODID = "instantunify";

	static Object resourceHandler = null;

	//config
	public static Configuration config;
	public static List<String> blacklist, preferredMods, blacklistMods;
	public static boolean drop, harvest, gui, second, useUnidict;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		config = new Configuration(event.getSuggestedConfigurationFile());
		blacklist = new ArrayList<String>(Arrays.asList(config.getStringList("blacklist", Configuration.CATEGORY_GENERAL, new String[] { "stair.*", "fence.*" }, "OreDict names that shouldn't be unified. (supports regex)")));
		preferredMods = new ArrayList<String>(Arrays.asList(config.getStringList("preferredMods", Configuration.CATEGORY_GENERAL, new String[] { "thermalfoundation", "immersiveengineering", "embers" }, "Preferred Mods")));
		preferredMods.add(0, "minecraft");
		blacklistMods = new ArrayList<String>(Arrays.asList(config.getStringList("blacklistMods", Configuration.CATEGORY_GENERAL, new String[] {}, "Blacklisted Mods")));
		drop = config.getBoolean("drop", "unifyEvent", true, "Unify when items drop.");
		harvest = config.getBoolean("harvest", "unifyEvent", true, "Unify when blocks are harvested.");
		second = config.getBoolean("second", "unifyEvent", false, "Unify every second items in player's inventory.");
		gui = config.getBoolean("gui", "unifyEvent", true, "Unify when GUI is opened/closed.");
		useUnidict = config.getBoolean("useUnidict", Configuration.CATEGORY_GENERAL, true, "Use UniDict's favorite ores to unify.") && Loader.isModLoaded("unidict");

		if (config.hasChanged())
			config.save();
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		if (useUnidict)
			resourceHandler = UniDict.getResourceHandler();
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void spawn(EntityJoinWorldEvent event) {
		if (event.getEntity() instanceof EntityItem && drop) {
			EntityItem ei = (EntityItem) event.getEntity();
			ei.setItem(replace(ei.getItem()));
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void drop(HarvestDropsEvent event) {
		for (int i = 0; i < event.getDrops().size() && harvest; i++) {
			event.getDrops().replaceAll(InstantUnify::replace);
		}
	}

	@SubscribeEvent
	public static void player(PlayerTickEvent event) {
		if (event.phase == Phase.END && event.side.isServer() && event.player.ticksExisted % 20 == 0 && second) {
			event.player.inventory.setItemStack(replace(event.player.inventory.getItemStack()));
			boolean changed = false;
			for (int i = 0; i < event.player.inventory.getSizeInventory(); i++) {
				ItemStack slot = event.player.inventory.getStackInSlot(i);
				ItemStack rep = replace(slot);
				if (!slot.isItemEqual(rep)) {
					event.player.inventory.setInventorySlotContents(i, rep);
					changed = true;
				}
			}
			if (changed && event.player.openContainer != null)
				event.player.openContainer.detectAndSendChanges();
		}
	}

	@SubscribeEvent
	public static void open(PlayerContainerEvent event) {
		if (gui)
			event.getContainer().inventorySlots.forEach(slot -> {
				if (slot.inventory instanceof InventoryPlayer)
					slot.inventory.setInventorySlotContents(slot.getSlotIndex(), replace(slot.inventory.getStackInSlot(slot.getSlotIndex())));
			});
	}

	private static ItemStack replace(ItemStack orig) {
		if (orig.isEmpty())
			return orig;
		if (useUnidict)
			return ((ResourceHandler) resourceHandler).getMainItemStack(orig);
		int[] ia = OreDictionary.getOreIDs(orig);
		if (ia.length != 1)
			return orig;
		if (blacklistMods.contains(orig.getItem().getRegistryName().getResourceDomain()))
			return orig;
		for (String s : blacklist)
			if (Pattern.matches(s, OreDictionary.getOreName(ia[0])))
				return orig;
		List<ItemStack> stacks = OreDictionary.getOres(OreDictionary.getOreName(ia[0])).stream().//
				sorted((s1, s2) -> {
					int i1 = preferredMods.indexOf(s1.getItem().getRegistryName().getResourceDomain()), i2 = preferredMods.indexOf(s2.getItem().getRegistryName().getResourceDomain());
					return Integer.compare(i1 == -1 ? 999 : i1, i2 == -1 ? 999 : i2);
				}).collect(Collectors.toList());
		if (stacks.stream().map(s -> s.getItem().getRegistryName().getResourceDomain()).distinct().count() == 1)
			return orig;
		for (ItemStack s : stacks) {
			if (Arrays.equals(ia, OreDictionary.getOreIDs(s)) && s.getItemDamage() != OreDictionary.WILDCARD_VALUE) {
				return ItemHandlerHelper.copyStackWithSize(s, orig.getCount());
			}
		}
		return orig;
	}

}
