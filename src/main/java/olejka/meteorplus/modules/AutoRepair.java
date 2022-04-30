package olejka.meteorplus.modules;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ToolItem;
import olejka.meteorplus.MeteorPlus;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.item.ItemStack;

import java.time.LocalDateTime;

public class AutoRepair extends Module {
	public AutoRepair() {
		super(MeteorPlus.CATEGORY, "Auto Repair", "Automatically repairs the item in the slot.");
	}

	private final SettingGroup ARSettings = settings.createGroup("Auto Repair Settings");

	private final Setting<Integer> breakDurability = ARSettings.add(new IntSetting.Builder()
		.name("Durability-percentage")
		.description("The durability percentage to repair a tool.")
		.defaultValue(10)
		.range(1, 100)
		.sliderRange(1, 100)
		.build()
	);

	private final Setting<Integer> Interval = ARSettings.add(new IntSetting.Builder()
		.name("Event interval")
		.defaultValue(3)
		.description("Don't touch if you don't know what it does.")
		.range(0, 10)
		.build()
	);

	private final Setting<String> Command = ARSettings.add(new StringSetting.Builder()
		.name("Command")
		.description("Repair command.")
		.defaultValue("/repair")
		.build()
	);

	@EventHandler(priority = EventPriority.HIGH)
	private void onStartBreakingBlock(StartBreakingBlockEvent event) {
		if (mc.player == null) return;
		ItemStack currentStack = mc.player.getMainHandStack();
		if (shouldStopUsing(currentStack) && isTool(currentStack)) {
			mc.options.keyAttack.setPressed(false);
			if (LocalDateTime.now().isBefore(start.plusSeconds(Interval.get()))) return;
			info("Repairing §2" + currentStack.getItem().getName().getString());
			mc.player.sendChatMessage(Command.get());
			start = LocalDateTime.now();
		}
	}

	LocalDateTime start = LocalDateTime.now().minusSeconds(4);
	public static boolean isTool(ItemStack itemStack) {
		return itemStack.getItem() instanceof ToolItem || itemStack.getItem() instanceof ShearsItem;
	}
	private boolean shouldStopUsing(ItemStack itemStack) {
		return (itemStack.getMaxDamage() - itemStack.getDamage()) < (itemStack.getMaxDamage() * breakDurability.get() / 100);
	}
}
