package olejka.meteorplus.modules;

import olejka.meteorplus.MeteorPlus;

import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Material;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class XrayBruteforce extends Module {
    public XrayBruteforce() {
        super(MeteorPlus.CATEGORY, "xray-bruteForce", "Bypasses anti-xray. (thx Nekiplay)");
    }
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgVisual = settings.createGroup("Visual");

    private final Setting<List<Block>> whblocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("whitelist")
        .description("Which blocks to show x-rayed.")
        .defaultValue(
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.ANCIENT_DEBRIS
        )
        .onChanged(v -> scanned.clear())
        .build()
    );

    private final Setting<PacketMode> packetmode = sgGeneral.add(new EnumSetting.Builder<PacketMode>()
        .name("packet-mode")
        .description("Packet mode.")
        .defaultValue(PacketMode.Abort)
        .build()
    );

    public enum PacketMode
    {
        Start,
        Abort,
        Both,
    }

    public final Setting<Boolean> clear_cache_blocks = sgGeneral.add(new BoolSetting.Builder()
        .name("Clear-cache")
        .description("Clear saved cache.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> clear_cache_ores = sgGeneral.add(new BoolSetting.Builder()
        .name("Clear-rendered-ores-cache")
        .description("Clear saved ores cache.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> fps_sync = sgGeneral.add(new BoolSetting.Builder()
        .name("FPS-sync")
        .description("FPS sync scaning.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> scan_cluster = sgGeneral.add(new BoolSetting.Builder()
        .name("Clusters")
        .description("Scan clusters.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> auto_height = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto-height")
        .description("Auto detect height.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> outline = sgVisual.add(new BoolSetting.Builder()
        .name("outline")
        .description("Outline to block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgVisual.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Outline color to block.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .visible(outline::get)
        .build()
    );

    private final Setting<Boolean> tracer = sgVisual.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Tracer to block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgVisual.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer color to block.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .visible(tracer::get)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Bruteforce range.")
        .defaultValue(40)
        .min(3)
        .sliderRange(3, 512)
        .build()
    );
    private final Setting<Integer> y_range = sgGeneral.add(new IntSetting.Builder()
        .name("y-range")
        .description("Bruteforce range.")
        .defaultValue(13)
        .min(3)
        .sliderRange(3, 255)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Bruteforce delay.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<XrayBruteforce.Mode> mode = sgGeneral.add(new EnumSetting.Builder<XrayBruteforce.Mode>()
        .name("mode")
        .description("Scan mode.")
        .defaultValue(Mode.Normal)
        .build()
    );

    private BlockPos currentScanBlock;
    public static class RenderOre {
        public Block block = Blocks.AIR;
        public BlockPos blockPos = null;
        public SettingColor color = null;
    }
    private static final List<RenderOre> ores = new ArrayList<>();
    private RenderOre get(BlockPos pos) {
        synchronized (ores) {
			for (RenderOre cur : ores) {
				if (cur.block != null && cur.blockPos.equals(pos)) {
					return cur;
				}
			}
            return null;
        }
    }
    private void addRenderBlock(BlockPos blockPos) {
        synchronized (ores) {
            RenderOre ore = get(blockPos);
            if (ore == null) {
                RenderOre ne = new RenderOre();
                ne.blockPos = blockPos;
                ores.add(ne);
            }
        }
    }

    @EventHandler
    public void blockUpdate(BlockUpdateEvent event) {
        scanned.add(event.pos);
        new Thread(() -> {
            if (event.oldState.getBlock() != Blocks.AIR) {
				assert mc.world != null;
				if (whblocks.get().contains(mc.world.getBlockState(event.pos).getBlock())) {
                    if (scan_cluster.get()) {
                        if (detectOre(event.newState.getBlock())) {
                            addRenderBlock(event.pos);

							for (BlockPos pos : getBlocks(event.pos, 3, 3)) {
								addBlock(pos);
							}
                        }
                    }
                } else {
                    RenderOre ore = get(event.pos);
                    if (ore != null) {
                        ores.remove(ore);
                        scanned.remove(event.pos);
                    }
                }
            }
        }).start();
    }

    @EventHandler
    private void minedBlock(BreakBlockEvent event) {
        new Thread(() -> {
            RenderOre ore = get(event.blockPos);
            if (ore != null) {
                ore.block = Blocks.AIR;
                ores.remove(ore);
            }
        }).start();
    }

    private void addBlock(BlockPos pos) {
        if (!scanned.contains(pos)) {
            blocks.add(pos);
        }
    }

    private boolean detectOre(Block block) {
        if (block == Blocks.ANCIENT_DEBRIS) {
            return true;
        }
		if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return true;
        }
		if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return true;
        }
		if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE) {
            return true;
        }
		if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            return true;
        }
		if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return true;
        }
		if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return true;
        }
		return block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE;
	}
    private void setColors(RenderOre ore)
    {
        if (ore != null && ore.block != null && ore.color == null) {
			assert mc.world != null;
			BlockState state = mc.world.getBlockState(ore.blockPos);
            if (ore.block == Blocks.AIR) {
                ore.block = state.getBlock();
            }
            if (state.getBlock() == Blocks.EMERALD_ORE || state.getBlock() == Blocks.DEEPSLATE_EMERALD_ORE) {
                ore.color = new SettingColor(0, 179, 60);
            } else if (state.getBlock() == Blocks.DIAMOND_ORE || state.getBlock() == Blocks.DEEPSLATE_DIAMOND_ORE) {
                ore.color = new SettingColor(0, 153, 255);
            } else if (state.getBlock() == Blocks.GOLD_ORE || state.getBlock() == Blocks.GOLD_ORE || state.getBlock() == Blocks.NETHER_GOLD_ORE) {
                ore.color = new SettingColor(255, 255, 0);
            } else if (state.getBlock() == Blocks.IRON_ORE || state.getBlock() == Blocks.DEEPSLATE_IRON_ORE) {
                ore.color = new SettingColor(128, 128, 128);
            } else if (state.getBlock() == Blocks.REDSTONE_ORE || state.getBlock() == Blocks.DEEPSLATE_REDSTONE_ORE) {
                ore.color = new SettingColor(0, 0, 153);
            } else if (state.getBlock() == Blocks.LAPIS_ORE || state.getBlock() == Blocks.DEEPSLATE_LAPIS_ORE) {
                ore.color = new SettingColor(0, 0, 153);
            } else if (state.getBlock() == Blocks.COAL_ORE || state.getBlock() == Blocks.DEEPSLATE_COAL_ORE) {
                ore.color = new SettingColor(0, 0, 0);
            }
        }
    }

    private void renderOres(Render3DEvent event)
    {
        int renderBlocks = 0;
		for (RenderOre pos : ores) {
			setColors(pos);
			if (EntityUtils.isInRenderDistance(pos.blockPos)) {
				renderOreBlock(event, pos);
				renderBlocks++;
			}
		}
        renderedBlocks = renderBlocks;
    }
    private int renderedBlocks = 0;
    private void renderOreBlock(Render3DEvent event, RenderOre ore)
    {
        if (ore.block != null && ore.color != null) {
			assert mc.world != null;
			BlockState state = mc.world.getBlockState(ore.blockPos);
            VoxelShape shape = state.getOutlineShape(mc.world, ore.blockPos);
            for (Box b : shape.getBoundingBoxes()) {
                event.renderer.box(ore.blockPos.getX() + b.minX, ore.blockPos.getY() + b.minY, ore.blockPos.getZ() + b.minZ, ore.blockPos.getX() + b.maxX, ore.blockPos.getY() + b.maxY, ore.blockPos.getZ() + b.maxZ, ore.color, ore.color, ShapeMode.Lines, 0);
            }
        }
    }
    private boolean lagging = false;
    @EventHandler
    private void render2d(Render2DEvent event)
    {
        lagging = false;
    }
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (clear_cache_blocks.get())
        {
            scanned.clear();
            clear_cache_blocks.set(false);
            ChatUtils.info("Xray BruteForce", "Cache checked blocks cleared");
        }
        if (clear_cache_ores.get())
        {
            ores.clear();
            clear_cache_ores.set(false);
            ChatUtils.info("Xray BruteForce", "Cache render ores cleared");
        }
        renderOres(event);
        if (currentScanBlock != null) {
            BlockPos bp = currentScanBlock;
			assert mc.world != null;
			BlockState state = mc.world.getBlockState(bp);
            VoxelShape shape = state.getOutlineShape(mc.world, bp);

            if (shape.isEmpty()) return;
            if (outline.get()) {
                for (Box b : shape.getBoundingBoxes()) {
                    event.renderer.box(bp.getX() + b.minX, bp.getY() + b.minY, bp.getZ() + b.minZ, bp.getX() + b.maxX, bp.getY() + b.maxY, bp.getZ() + b.maxZ, new SettingColor(255, 255, 255, 255), outlineColor.get(), ShapeMode.Lines, 0);
                }
            }
            if (tracer.get()) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, bp.getX(), bp.getY(), bp.getZ(), tracerColor.get());
            }
        }
    }

    private List<BlockPos> blocks = new ArrayList<>();
    @Override
    public String getInfoString() {
        if (calculating) {
            return "calculating";
        }
        else if ((long) blocks.size() > 1) {
            return (long) blocks.size() + " | rendered: " + renderedBlocks + " blocks";
        }
        else if (mode.get() == Mode.Random)
        {
            return "finding | rendered: " + renderedBlocks + " blocks";
        }
        else
        {
            return null;
        }
    }

    private boolean send(BlockPos blockpos)
    {
        if (blockpos == null)
            return false;
        if (scanned.contains(blockpos))
            return false;
        ClientPlayNetworkHandler conn = mc.getNetworkHandler();
        if (conn == null)
            return false;
        currentScanBlock = blockpos;
        PlayerActionC2SPacket startPacket = new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, new BlockPos(blockpos), Direction.UP);
        PlayerActionC2SPacket abortPacket = new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, new BlockPos(blockpos), Direction.UP);
        if (packetmode.get() == PacketMode.Start || packetmode.get() == PacketMode.Both) {
            conn.sendPacket(startPacket);
        }
        if (packetmode.get() == PacketMode.Abort || packetmode.get() == PacketMode.Both) {
            conn.sendPacket(abortPacket);
        }
        return true;
    }
    long millis = 0;
    private void checker()
    {
        if (LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() >= millis)
        {
            if (blocks != null && blocks.size() > 0) {
                work();
            }
            else if (mode.get() == Mode.Random)
            {
                addRandomBlock();
                work();
            }
        }
    }

    private void work()
    {
        try {
            Iterator<BlockPos> blocksIterator = blocks.iterator();
            if (blocksIterator.hasNext()) {
                if (fps_sync.get()) {
                    if (!lagging) {
                        BlockPos block = blocksIterator.next();
                        blocksIterator.remove();
                        if (send(block)) {
                            millis = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + delay.get();
                            lagging = true;
                        }
                    }
                }
                else
                {
                    BlockPos block = blocksIterator.next();
                    blocksIterator.remove();
                    if (send(block)) {
                        millis = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + delay.get();
                        lagging = true;
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private void addRandomBlock() {
		assert mc.player != null;
		int x = Utils.random(mc.player.getBlockPos().getX() -range.get(), mc.player.getBlockPos().getX() + range.get());
        int y;
        int z = Utils.random(mc.player.getBlockPos().getZ() -range.get(), mc.player.getBlockPos().getZ() + range.get());

        if (auto_height.get())
        {
            if (whblocks.get().contains(Blocks.DIAMOND_ORE) || whblocks.get().contains(Blocks.DEEPSLATE_DIAMOND_ORE))
            {
                y = Utils.random(1, 15);
                if (!scanned.contains(new BlockPos(x, y, z))) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
            if (whblocks.get().contains(Blocks.REDSTONE_ORE) || whblocks.get().contains(Blocks.DEEPSLATE_REDSTONE_ORE))
            {
                y = Utils.random(1, 15);
                if (!scanned.contains(new BlockPos(x, y, z))) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
            if (whblocks.get().contains(Blocks.LAPIS_ORE) || whblocks.get().contains(Blocks.DEEPSLATE_LAPIS_ORE))
            {
                y = Utils.random(1, 31);
                if (!scanned.contains(new BlockPos(x, y, z))) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
            if (whblocks.get().contains(Blocks.GOLD_ORE) || whblocks.get().contains(Blocks.DEEPSLATE_GOLD_ORE))
            {
                y = Utils.random(1, 32);
                if (!scanned.contains(new BlockPos(x, y, z))) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
            if (whblocks.get().contains(Blocks.IRON_ORE) || whblocks.get().contains(Blocks.DEEPSLATE_IRON_ORE))
            {
                y = Utils.random(1, 63);
                if (!scanned.contains(new BlockPos(x, y, z))) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
            if (whblocks.get().contains(Blocks.COAL_ORE) || whblocks.get().contains(Blocks.DEEPSLATE_COAL_ORE))
            {
                y = Utils.random(1, 114);
                if (!scanned.contains(new BlockPos(x, y, z))) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
        }
        else
        {
            y = Utils.random(mc.player.getBlockPos().getY() -y_range.get(), mc.player.getBlockPos().getY() + y_range.get());
            if (!scanned.contains(new BlockPos(x, y, z))) {
                blocks.add(new BlockPos(x, y, z));
            }
        }
    }
    private Thread clickerThread;
    @Override
    public void onActivate() {
        millis = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        clickerThread = new Thread(() -> {
            if (mode.get() == Mode.Normal) {
				assert mc.player != null;
				blocks = getBlocks(mc.player.getBlockPos(), y_range.get(), range.get());
            }
            else if (mode.get() == Mode.Random) {
                addRandomBlock();
            }
            while (true)
            {
                checker();
            }
        });
        clickerThread.start();
    }

    @Override
    public void onDeactivate() {
        blocks.clear();
        currentScanBlock = null;
        if (clickerThread != null && clickerThread.isAlive())
        {
            clickerThread.interrupt();
        }
    }

    private static final List<BlockPos> scanned = new ArrayList<>();
    private boolean calculating = false;

    private List<BlockPos> getNearblyBlocks(BlockPos startPos, Block block)
    {
        List<BlockPos> nearbly = new ArrayList<>();

        BlockPos pos1 = startPos.add(1, 0, 0);
		assert mc.world != null;
		if (mc.world.getBlockState(pos1).getBlock() == block)
        {
            nearbly.add(pos1);
        }
        BlockPos pos2 = startPos.add(-1, 0, 0);
        if (mc.world.getBlockState(pos2).getBlock() == block)
        {
            nearbly.add(pos2);
        }

        BlockPos pos3 = startPos.add(0, 0, 1);
        if (mc.world.getBlockState(pos3).getBlock() == block)
        {
            nearbly.add(pos3);
        }

        BlockPos pos4 = startPos.add(0, 0, -1);
        if (mc.world.getBlockState(pos4).getBlock() == block)
        {
            nearbly.add(pos4);
        }

        BlockPos pos5 = startPos.add(0, 1, 0);
        if (mc.world.getBlockState(pos5).getBlock() == block)
        {
            nearbly.add(pos5);
        }
        BlockPos pos6 = startPos.add(0, -1, 0);
        if (mc.world.getBlockState(pos6).getBlock() == block)
        {
            nearbly.add(pos6);
        }
        return nearbly;
    }

    private List<BlockPos> getBlocks(BlockPos startPos, int y_radius, int radius)
    {
        List<BlockPos> temp = new ArrayList<>();
        calculating = true;
        for (int dy = -y_radius; dy <= y_radius; dy++) {
            if ((startPos.getY() + dy) < 1 || (startPos.getY() + dy) > 255) continue;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos blockPos = new BlockPos(startPos.getX() + dx, startPos.getY() + dy, startPos.getZ() + dz);
                    if (EntityUtils.isInRenderDistance(blockPos)) {
						assert mc.world != null;
						BlockState state = mc.world.getBlockState(blockPos);
                        if (state.getMaterial() == Material.STONE) {
                            if (!scanned.contains(blockPos)) {
                                temp.add(blockPos);
                            }
                        }
                    }
                }
            }
        }
        calculating = false;
        return temp;
    }

    public enum Mode {
		Normal,
        Random,
    }
}