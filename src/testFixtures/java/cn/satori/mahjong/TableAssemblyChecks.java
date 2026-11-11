package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

import java.util.ArrayList;

/** Exercises the production BlockItem placement, obstructed footprint, drops and loaded recipe. */
public final class TableAssemblyChecks {
    public static void verify(ServerLevel level, BlockPos center) {
        for (var pos : TableAssembly.footprint(center)) {
            level.getChunkAt(pos);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        }
        var anchor = center.below();
        var old = level.getBlockState(anchor);
        level.setBlockAndUpdate(anchor, Blocks.STONE.defaultBlockState());
        var obstruction = TableSeating.chair(center, 1);
        try {
            level.setBlockAndUpdate(obstruction, Blocks.STONE.defaultBlockState());
            place(level, center);
            if (TableBlock.complete(level, center) || !level.getBlockState(center).isAir())
                throw new AssertionError("Obstructed placement partially wrote the table");
            if (!level.getBlockState(obstruction).is(Blocks.STONE))
                throw new AssertionError("Obstructed placement overwrote a block");
            level.setBlockAndUpdate(obstruction, Blocks.AIR.defaultBlockState());
            place(level, center);
            if (!TableBlock.complete(level, center))
                throw new AssertionError("One item did not assemble table");
            for (int seat = 0; seat < 4; seat++)
                if (!level.getBlockState(TableSeating.chair(center, seat))
                        .is(SatoriMahjong.chair()))
                    throw new AssertionError("Chair absent after placement");
            for (var pos : TableAssembly.footprint(center))
                if (!pos.equals(center) && level.getBlockEntity(pos) != null)
                    throw new AssertionError("Furniture part created a second table entity");
            level.destroyBlock(center.offset(1, 0, 1), true);
            for (var pos : TableAssembly.footprint(center))
                if (!level.getBlockState(pos).isAir())
                    throw new AssertionError("Orphan furniture " + pos);
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(4));
            int count =
                    drops.stream()
                            .filter(e -> e.getItem().is(SatoriMahjong.table().asItem()))
                            .mapToInt(e -> e.getItem().getCount())
                            .sum();
            if (count != 1)
                throw new AssertionError("Expected exactly one table drop, got " + count);
            drops.forEach(ItemEntity::discard);
            for (Item item : BuiltInRegistries.ITEM) {
                var stack = new ItemStack(item);
                if (stack.is(ItemTags.LOGS)) recipe(level, item, Items.WHITE_CARPET);
                if (stack.is(ItemTags.WOOL_CARPETS)) recipe(level, Items.OAK_LOG, item);
            }
            if (BuiltInRegistries.ITEM.containsKey(SatoriMahjong.id("table_frame")))
                throw new AssertionError("Obsolete frame item registered");
        } finally {
            level.destroyBlock(center, false);
            level.setBlockAndUpdate(anchor, old);
        }
    }

    private static void place(ServerLevel level, BlockPos center) {
        var stack = new ItemStack(SatoriMahjong.table());
        var hit = new BlockHitResult(center.getBottomCenter(), Direction.UP, center.below(), false);
        ((BlockItem) stack.getItem())
                .place(
                        new net.minecraft.world.item.context.BlockPlaceContext(
                                new UseOnContext(
                                        level, null, InteractionHand.MAIN_HAND, stack, hit)));
    }

    private static void recipe(ServerLevel level, Item log, Item carpet) {
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++) slots.add(new ItemStack(i == 4 ? log : carpet));
        var recipe =
                level.getServer()
                        .getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, slots), level)
                        .orElseThrow();
        if (!recipe.id().identifier().equals(SatoriMahjong.id("table")))
            throw new AssertionError("Carpet/log recipe did not select MahjongTable");
    }
}
