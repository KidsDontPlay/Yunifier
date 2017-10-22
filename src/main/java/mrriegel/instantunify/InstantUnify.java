package mrriegel.instantunify;

import static net.minecraftforge.common.config.Configuration.CATEGORY_GENERAL;
import static net.minecraftforge.common.config.Configuration.NEW_LINE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.oredict.OreDictionary;

@Mod(modid = InstantUnify.MODID, name = InstantUnify.NAME, version = InstantUnify.VERSION, dependencies = "before:unidict", acceptedMinecraftVersions = "[1.12,1.13)", acceptableRemoteVersions = "*")
@EventBusSubscriber
public class InstantUnify {

	@Instance(InstantUnify.MODID)
	public static InstantUnify INSTANCE;

	public static final String VERSION = "1.0.6";
	public static final String NAME = "InstantUnify";
	public static final String MODID = "instantunify";

	//config
	public static Configuration config;
	public static List<String> blacklist, whitelist, preferredMods, blacklistMods;
	public static boolean drop, harvest, gui, second, useUnidict;
	public static int listMode;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		config = new Configuration(event.getSuggestedConfigurationFile());
		blacklist = new ArrayList<String>(Arrays.asList(config.getStringList("blacklist", "List", new String[] { ".*Wood", ".*Glass.*", "stair.*", "fence.*", "plank.*", "slab.*" }, "OreDict names that shouldn't be unified. (supports regex)" + NEW_LINE)));
		whitelist = new ArrayList<String>(Arrays.asList(config.getStringList("whitelist", "List", new String[] { "block.*", "chunk.*", "dust.*", "dustSmall.*", "dustTiny.*", "gear.*", "gem.*", "ingot.*", "nugget.*", "ore.*", "plate.*", "rod.*" }, "OreDict names that should be unified. (supports regex)" + NEW_LINE)));
		listMode = config.getInt("listMode", "List", 2, 0, 3, "0 - use whitelist" + NEW_LINE + "1 - use blacklist" + NEW_LINE + "2 - use both lists" + NEW_LINE + "3 - use no list" + NEW_LINE);
		preferredMods = new ArrayList<String>(Arrays.asList(config.getStringList("preferredMods", CATEGORY_GENERAL, new String[] { "thermalfoundation", "immersiveengineering", "embers" }, "Preferred Mods" + NEW_LINE)));
		preferredMods.add(0, "minecraft");
		blacklistMods = new ArrayList<String>(Arrays.asList(config.getStringList("blacklistMods", CATEGORY_GENERAL, new String[] { "chisel" }, "Blacklisted Mods" + NEW_LINE)));
		drop = config.getBoolean("drop", "unifyEvent", true, "Unify when items drop.");
		harvest = config.getBoolean("harvest", "unifyEvent", true, "Unify when blocks are harvested.");
		second = config.getBoolean("second", "unifyEvent", false, "Unify every second items in player's inventory.");
		gui = config.getBoolean("gui", "unifyEvent", true, "Unify when GUI is opened/closed.");
		//useUnidict = config.getBoolean("useUnidict", CATEGORY_GENERAL, true, "Use UniDict's favorite ores to unify.") && Loader.isModLoaded("unidict");

		if (config.hasChanged())
			config.save();
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void spawn(EntityJoinWorldEvent event) {
		if (drop && event.getEntity() instanceof EntityItem) {
			EntityItem ei = (EntityItem) event.getEntity();
			ei.setItem(replace(ei.getItem()));
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void harvest(HarvestDropsEvent event) {
		for (int i = 0; harvest && i < event.getDrops().size(); i++) {
			try {
				event.getDrops().replaceAll(InstantUnify::replace);
			} catch (UnsupportedOperationException e) {
			}
		}
	}

	@SubscribeEvent
	public static void player(PlayerTickEvent event) {
		if (second && event.phase == Phase.END && event.side.isServer() && event.player.ticksExisted % 20 == 0) {
			event.player.inventory.setItemStack(replace(event.player.inventory.getItemStack()));
			boolean changed = false;
			for (int i = 0; i < event.player.inventory.getSizeInventory(); i++) {
				ItemStack slot = event.player.inventory.getStackInSlot(i);
				Optional<ItemStack> op = replaceOptional(slot);
				if (op.isPresent()) {
					event.player.inventory.setInventorySlotContents(i, op.get());
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
			event.getContainer().inventorySlots.stream().filter(slot -> slot.inventory instanceof InventoryPlayer).forEach(slot -> slot.inventory.setInventorySlotContents(slot.getSlotIndex(), replace(slot.inventory.getStackInSlot(slot.getSlotIndex()))));
	}

	private static ItemStack replace(ItemStack orig) {
		Optional<ItemStack> op = replaceOptional(orig);
		return op.isPresent() ? op.get() : orig;
	}

	private static Optional<ItemStack> replaceOptional(ItemStack orig) {
		if (orig.isEmpty())
			return Optional.empty();
		if (useUnidict && ".".isEmpty()/** TODO disabled */
		) {
		}
		int[] ia = OreDictionary.getOreIDs(orig);
		if (ia.length == 0)
			return Optional.empty();
		int ore = ia[0];
		if (blacklistMods.contains(orig.getItem().getRegistryName().getResourceDomain()))
			return Optional.empty();
		if (listMode == 0 || listMode == 2) {
			if (!whitelist.stream().anyMatch(s -> Pattern.matches(s, OreDictionary.getOreName(ore))))
				return Optional.empty();
		}
		if (listMode == 1 || listMode == 2) {
			if (blacklist.stream().anyMatch(s -> Pattern.matches(s, OreDictionary.getOreName(ore))))
				return Optional.empty();
		}
		List<ItemStack> stacks = OreDictionary.getOres(OreDictionary.getOreName(ore)).stream().//
				sorted((s1, s2) -> {
					int i1 = preferredMods.indexOf(s1.getItem().getRegistryName().getResourceDomain()), i2 = preferredMods.indexOf(s2.getItem().getRegistryName().getResourceDomain());
					return Integer.compare(i1 == -1 ? 999 : i1, i2 == -1 ? 999 : i2);
				}).collect(Collectors.toList());
		if (stacks.stream().map(s -> s.getItem().getRegistryName().getResourceDomain()).distinct().count() == 1)
			return Optional.empty();
		for (ItemStack s : stacks) {
			if (Arrays.equals(ia, OreDictionary.getOreIDs(s))) {
				if (s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
					if (s.getItem() == orig.getItem())
						return Optional.empty();
				} else {
					ItemStack res = ItemHandlerHelper.copyStackWithSize(s, orig.getCount());
					return res.isItemEqual(orig) ? Optional.empty() : Optional.of(res);
				}
			}
		}
		return Optional.empty();
	}

}
