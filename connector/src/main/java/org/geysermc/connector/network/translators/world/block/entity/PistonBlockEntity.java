/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.world.block.entity;

import com.github.steveice10.mc.protocol.data.game.world.block.value.PistonValue;
import com.github.steveice10.mc.protocol.data.game.world.block.value.PistonValueType;
import com.google.common.collect.ImmutableList;
import com.nukkitx.math.vector.Vector3i;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtMapBuilder;
import com.nukkitx.protocol.bedrock.packet.BlockEntityDataPacket;
import com.nukkitx.protocol.bedrock.packet.UpdateBlockPacket;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.world.block.BlockStateValues;
import org.geysermc.connector.network.translators.world.block.BlockTranslator;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PistonBlockEntity {
    private final GeyserSession session;
    private final Vector3i position;
    private final PistonValue orientation;
    private final boolean sticky;

    private PistonValueType action;

    /**
     * A map of attached block positions to Java ids.
     */
    private final Object2IntMap<Vector3i> attachedBlocks = new Object2IntOpenHashMap<>();
    /**
     * A flattened array of the positions of attached blocks, stored in XYZ order.
     */
    private int[] flattenedAttachedBlocks = new int[0];

    /**
     * The position of the piston head
     */
    private float progress = 0.0f;
    private float lastProgress = 0.0f;

    private ScheduledFuture<?> updater;

    private static final NbtMap AIR_TAG = BlockTranslator.BLOCKS.get(BlockTranslator.AIR).getCompound("block");
    private static final List<Vector3i> ALL_DIRECTIONS = ImmutableList.of(Vector3i.from(1, 0, 0), Vector3i.from(0, 1, 0), Vector3i.from(0, 0, 1), Vector3i.from(-1, 0, 0), Vector3i.from(0, -1, 0), Vector3i.from(0, 0, -1));

    public PistonBlockEntity(GeyserSession session, Vector3i position, PistonValue orientation) {
        this.session = session;
        this.position = position;
        this.orientation = orientation;

        if (session.getConnector().getConfig().isCacheChunks()) {
            int blockId = session.getConnector().getWorldManager().getBlockAt(session, position);
            sticky = BlockStateValues.isStickyPiston(blockId);
            boolean extended = BlockStateValues.getPistonValues().get(blockId);
            if (extended) {
                this.action = PistonValueType.PUSHING;
                this.progress = 1.0f;
            } else {
                this.action = PistonValueType.PULLING;
                this.progress = 0.0f;
            }
            this.lastProgress = progress;
        } else {
            sticky = false;
        }
    }

    /**
     * Set whether the piston is pulling or pushing blocks
     * @param action Pulling or Pushing
     */
    public void setAction(PistonValueType action) {
        this.action = action;
        if (action == PistonValueType.CANCELLED_MID_PUSH) {
            // Immediately fully extend the piston
            progress = 1.0f;
            lastProgress = 1.0f;
        } else if (action == PistonValueType.PUSHING || (action == PistonValueType.PULLING && sticky)) {
            // Blocks only move when pushing
            // or pulling with sticky pistons
            findAffectedBlocks();
            removeBlocks();
            createMovingBlocks();
        }

        if (updater != null) {
            updater.cancel(true);
        }
    }

    /**
     * Send block entity data packets to update the position of the piston head
     */
    public void sendUpdate() {
        BlockEntityDataPacket blockEntityDataPacket = new BlockEntityDataPacket();
        blockEntityDataPacket.setBlockPosition(position);
        blockEntityDataPacket.setData(buildPistonTag());
        session.sendUpstreamPacket(blockEntityDataPacket);
        if (!isDone()) {
            if (action == PistonValueType.CANCELLED_MID_PUSH && progress == 1.0f) {
                finishMovingBlocks();
                attachedBlocks.clear();
                flattenedAttachedBlocks = new int[0];
            }
            updater = session.getConnector().getGeneralThreadPool().schedule(() -> {
                updateProgress();
                sendUpdate();
            }, 50, TimeUnit.MILLISECONDS);
        } else {
            if (action != PistonValueType.PUSHING) {
                removePistonHead();
            }
            finishMovingBlocks();
            session.getPistonCache().remove(position);
        }
    }

    /**
     * Removes lingering piston heads
     */
    private void removePistonHead() {
        Vector3i blockInfront = position.add(getDirectionOffset());
        int blockId = session.getConnector().getWorldManager().getBlockAt(session, blockInfront);
        String javaId = BlockTranslator.getJavaIdBlockMap().inverse().get(blockId);
        if (javaId.contains("piston_head")) {
            session.getChunkCache().updateBlock(blockInfront.getX(), blockInfront.getY(), blockInfront.getZ(), BlockTranslator.AIR);
        }
    }

    /**
     * Find the blocks pushed, pulled or broken by the piston
     */
    private void findAffectedBlocks() {
        attachedBlocks.clear();
        if (!session.getConnector().getConfig().isCacheChunks()) {
            flattenPositions();
            return;
        }
        Set<Vector3i> blocksChecked = new ObjectOpenHashSet<>();
        Queue<Vector3i> blocksToCheck = new LinkedList<>();

        Vector3i directionOffset = getDirectionOffset();
        Vector3i movement = getMovement();
        blocksChecked.add(position); // Don't check the piston itself
        if (action == PistonValueType.PULLING) {
            blocksChecked.add(position.add(directionOffset)); // Don't check the piston head
            blocksToCheck.add(position.add(directionOffset.mul(2)));
        } else if (action == PistonValueType.PUSHING) {
            removePistonHead();
            blocksToCheck.add(position.add(directionOffset));
        }

        boolean moveBlocks = true;
        while (!blocksToCheck.isEmpty()) {
            Vector3i blockPos = blocksToCheck.remove();
            // Skip blocks we've already checked
            if (blocksChecked.contains(blockPos)) {
                continue;
            }
            blocksChecked.add(blockPos);
            int blockId = session.getConnector().getWorldManager().getBlockAt(session, blockPos);
            if (blockId == BlockTranslator.AIR) {
                continue;
            }
            blocksChecked.add(blockPos);
            if (canMoveBlock(blockId, action == PistonValueType.PUSHING)) {
                attachedBlocks.put(blockPos, blockId);
                if (isBlockSticky(blockId)) {
                    // For honey blocks and slime blocks check the blocks adjacent to it
                    for (Vector3i offset : ALL_DIRECTIONS) {
                        // Only check blocks that aren't being pushed by the current block
                        if (offset.equals(movement)) {
                            continue;
                        }
                        Vector3i adjacentPos = blockPos.add(offset);
                        // Ignore the piston block itself
                        if (adjacentPos.equals(position)) {
                            continue;
                        }
                        // Ignore the piston head
                        if (action == PistonValueType.PULLING && position.add(directionOffset).equals(adjacentPos)) {
                            continue;
                        }
                        int adjacentBlockId = session.getConnector().getWorldManager().getBlockAt(session, adjacentPos);
                        if (adjacentBlockId != BlockTranslator.AIR && isBlockAttached(blockId, adjacentBlockId) && canMoveBlock(adjacentBlockId, false)) {
                            // If it is another slime/honey block we need to check its adjacent blocks
                            if (isBlockSticky(adjacentBlockId)) {
                                blocksToCheck.add(adjacentPos);
                            } else {
                                attachedBlocks.put(adjacentPos, adjacentBlockId);
                                blocksChecked.add(adjacentPos);
                                blocksToCheck.add(adjacentPos.add(movement));
                            }
                        }
                    }
                }
            } else if (!canDestroyBlock(blockId)) {
                // Block can't be moved or destroyed, so it blocks all block movement
                moveBlocks = false;
                break;
            }
            // Check next block in line
            blocksToCheck.add(blockPos.add(movement));
        }
        if (!moveBlocks) {
            attachedBlocks.clear();
        }
        flattenPositions();
    }

    private boolean canMoveBlock(int javaId, boolean isPushing) {
        if (javaId == BlockTranslator.AIR) {
            return true;
        }
        // Pistons can only be moved if they aren't extended
        if (PistonBlockEntityTranslator.isBlock(javaId)) {
            return !BlockStateValues.getPistonValues().get(javaId);
        }
        if (BlockTranslator.JAVA_RUNTIME_ID_TO_HARDNESS.get(javaId) == -1.0d) {
            return false;
        }
        String pistonBehavior = BlockTranslator.JAVA_RUNTIME_ID_TO_PISTON_BEHAVIOR.getOrDefault(javaId, "");
        switch (pistonBehavior) {
            case "block":
            case "destroy":
                return false;
            case "push_only":
                return isPushing;
        }
        // Pistons can't move tile entities
        return BlockTranslator.getBlockEntityString(javaId) == null;
    }

    private boolean canDestroyBlock(int javaId)  {
        return BlockTranslator.JAVA_RUNTIME_ID_TO_PISTON_BEHAVIOR.getOrDefault(javaId, "").equals("destroy");
    }

    /**
     * Checks if a block sticks to other blocks.
     * Slime and honey blocks.
     * @param javaId The block id
     * @return True if the block sticks to adjacent blocks
     */
    private boolean isBlockSticky(int javaId) {
        String javaIdentifier = BlockTranslator.getJavaIdBlockMap().inverse().getOrDefault(javaId, "");
        return javaIdentifier.equals("minecraft:slime_block") || javaIdentifier.equals("minecraft:honey_block");
    }

    /**
     * Check if two blocks are attached to each other
     * @param javaIdA The block id of block a
     * @param javaIdB The block id of block b
     * @return True if the blocks are attached to each other
     */
    private boolean isBlockAttached(int javaIdA, int javaIdB) {
        boolean aSticky = isBlockSticky(javaIdA);
        boolean bSticky = isBlockSticky(javaIdB);
        if (aSticky && bSticky) {
            // Only matching sticky blocks are attached together
            // Honey + Honey & Slime + Slime
            return javaIdA == javaIdB;
        }
        return aSticky || bSticky;
    }

    /**
     * Get the direction the piston head points in
     * @return A Vector3i pointing in the direction of the piston head
     */
    private Vector3i getDirectionOffset() {
        switch (orientation) {
            case DOWN:
                return Vector3i.from(0, -1, 0);
            case UP:
                return Vector3i.from(0, 1, 0);
            case SOUTH:
                return Vector3i.from(0, 0, 1);
            case WEST:
                return Vector3i.from(-1, 0, 0);
            case NORTH:
                return Vector3i.from(0, 0, -1);
            case EAST:
                return Vector3i.from(1, 0, 0);
        }
        return Vector3i.ZERO;
    }

    /**
     * Get the offset from the current position of the attached blocks
     * to the new positions
     * @return The movement of the blocks
     */
    private Vector3i getMovement() {
        if (action == PistonValueType.PULLING) {
            return getDirectionOffset().negate();
        }
        return getDirectionOffset(); // PUSHING and CANCELLED_MID_PUSH
    }

    /**
     * Replace all attached blocks with air
     */
    private void removeBlocks() {
        for (Vector3i blockPos : attachedBlocks.keySet()) {
            UpdateBlockPacket updateBlockPacket = new UpdateBlockPacket();
            updateBlockPacket.getFlags().add(UpdateBlockPacket.Flag.NEIGHBORS);
            updateBlockPacket.getFlags().add(UpdateBlockPacket.Flag.NETWORK);
            updateBlockPacket.setBlockPosition(blockPos);
            updateBlockPacket.setRuntimeId(BlockTranslator.AIR);
            updateBlockPacket.setDataLayer(0);
            session.sendUpstreamPacket(updateBlockPacket);
        }
    }

    /**
     * Create moving block entities for each attached block
     */
    private void createMovingBlocks() {
        Vector3i movement = getMovement();
        attachedBlocks.forEach((blockPos, javaId) -> {
            Vector3i newPos = blockPos.add(movement);
            // Get Bedrock block state data
            NbtMap blockTag = BlockTranslator.BLOCKS.get(BlockTranslator.getBedrockBlockId(javaId)).getCompound("block");
            // Place a moving block at the new location of the block
            UpdateBlockPacket updateBlockPacket = new UpdateBlockPacket();
            updateBlockPacket.getFlags().add(UpdateBlockPacket.Flag.NEIGHBORS);
            updateBlockPacket.getFlags().add(UpdateBlockPacket.Flag.NETWORK);
            updateBlockPacket.setBlockPosition(newPos);
            updateBlockPacket.setRuntimeId(BlockTranslator.BEDROCK_RUNTIME_MOVING_BLOCK_ID);
            updateBlockPacket.setDataLayer(0);
            session.sendUpstreamPacket(updateBlockPacket);
            // Update moving block with correct details
            BlockEntityDataPacket entityDataPacket = new BlockEntityDataPacket();
            entityDataPacket.setBlockPosition(newPos);
            if (PistonBlockEntityTranslator.isBlock(javaId)) {
                NbtMap pistonData = PistonBlockEntityTranslator.getTag(javaId, newPos);
                entityDataPacket.setData(buildMovingBlockTag(newPos, blockTag, pistonData, position));
            } else {
                entityDataPacket.setData(buildMovingBlockTag(newPos, blockTag, null, position));
            }
            session.sendUpstreamPacket(entityDataPacket);
        });
    }

    /**
     * Replace all moving block entities with the final block
     */
    private void finishMovingBlocks() {
        Vector3i movement = getMovement();
        attachedBlocks.forEach((blockPos, javaId) -> {
            blockPos = blockPos.add(movement);
            // Pistons seem to stick around even after the movement has finished
            // An extra block entity packet has to be sent to detach it
            if (PistonBlockEntityTranslator.isBlock(javaId)) {
                // Get Bedrock block state data
                NbtMap blockTag = BlockTranslator.BLOCKS.get(BlockTranslator.getBedrockBlockId(javaId)).getCompound("block");
                NbtMap pistonData = PistonBlockEntityTranslator.getTag(javaId, blockPos);

                BlockEntityDataPacket entityDataPacket = new BlockEntityDataPacket();
                entityDataPacket.setBlockPosition(blockPos);
                entityDataPacket.setData(buildMovingBlockTag(blockPos, blockTag, pistonData, Vector3i.from(0, -1, 0)));
                session.sendUpstreamPacket(entityDataPacket);
            }
            UpdateBlockPacket updateBlockPacket = new UpdateBlockPacket();
            updateBlockPacket.getFlags().add(UpdateBlockPacket.Flag.NEIGHBORS);
            updateBlockPacket.getFlags().add(UpdateBlockPacket.Flag.NETWORK);
            updateBlockPacket.setBlockPosition(blockPos);
            updateBlockPacket.setRuntimeId(BlockTranslator.getBedrockBlockId(javaId));
            updateBlockPacket.setDataLayer(0);
            session.sendUpstreamPacket(updateBlockPacket);
            // Piston block entity data
            if (PistonBlockEntityTranslator.isBlock(javaId)) {
                BlockEntityDataPacket blockEntityDataPacket = new BlockEntityDataPacket();
                blockEntityDataPacket.setBlockPosition(blockPos);
                blockEntityDataPacket.setData(PistonBlockEntityTranslator.getTag(javaId, blockPos));
                session.sendUpstreamPacket(blockEntityDataPacket);
            }
        });
    }

    /**
     * Flatten the positions of attached blocks into a 1D array
     */
    private void flattenPositions() {
        flattenedAttachedBlocks = new int[3 * attachedBlocks.size()];
        Iterator<Vector3i> attachedBlocksIterator = attachedBlocks.keySet().iterator();
        int i = 0;
        while (attachedBlocksIterator.hasNext()) {
            Vector3i position = attachedBlocksIterator.next();
            flattenedAttachedBlocks[3 * i] = position.getX();
            flattenedAttachedBlocks[3 * i + 1] = position.getY();
            flattenedAttachedBlocks[3 * i + 2] = position.getZ();
            i++;
        }
    }

    /**
     * Get the Bedrock state of the piston
     * @return 0 - Fully retracted, 1 - Extending, 2 - Fully extended, 3 - Retracting
     */
    private byte getState() {
        switch (action) {
            case PUSHING:
                return (byte) (isDone() ? 2 : 1);
            case PULLING:
                return (byte) (isDone() ? 0 : 3);
            default:
                if (progress == 1.0f) {
                    return 2;
                }
                return (byte) (isDone() ? 0 : 2);
        }
    }

    /**
     * Update the progress or position of the piston head
     */
    private void updateProgress() {
        switch (action) {
            case PUSHING:
                lastProgress = progress;
                progress += 0.5f;
                if (progress >= 1.0f) {
                    progress = 1.0f;
                }
                break;
            case CANCELLED_MID_PUSH:
            case PULLING:
                lastProgress = progress;
                progress -= 0.5f;
                if (progress <= 0.0f) {
                    progress = 0.0f;
                }
                break;
        }
    }

    /**
     * @return True if the piston has finished it's movement, otherwise false
     */
    public boolean isDone() {
        switch (action) {
            case PUSHING:
                return progress == 1.0f && lastProgress == 1.0f;
            case PULLING:
            case CANCELLED_MID_PUSH:
                return progress == 0.0f && lastProgress == 0.0f;
        }
        return true;
    }

    /**
     * Create a piston data tag with the data in this block entity
     * @return A piston data tag
     */
    private NbtMap buildPistonTag() {
        NbtMapBuilder builder = NbtMap.builder()
                .putString("id", "PistonArm")
                .putIntArray("AttachedBlocks", flattenedAttachedBlocks)
                .putFloat("Progress", progress)
                .putFloat("LastProgress", lastProgress)
                .putByte("NewState", getState())
                .putByte("State", getState())
                .putByte("Sticky", (byte) (sticky ? 1 : 0))
                .putByte("isMovable", (byte) 0)
                .putInt("x", position.getX())
                .putInt("y", position.getY())
                .putInt("z", position.getZ());
        return builder.build();
    }

    /**
     * Create a piston data tag that has fully extended/retracted
     * @param position The position for the base of the piston
     * @param extended If the piston is extended or not
     * @param sticky If the piston is a sticky piston or not
     * @return A piston data tag for a fully extended/retracted piston
     */
    public static NbtMap buildStaticPistonTag(Vector3i position, boolean extended, boolean sticky) {
        NbtMapBuilder builder = NbtMap.builder()
                .putString("id", "PistonArm")
                .putFloat("Progress", extended ? 1.0f : 0.0f)
                .putFloat("LastProgress", extended ? 1.0f : 0.0f)
                .putByte("NewState", (byte) (extended ? 2 : 0))
                .putByte("State", (byte) (extended ? 2 : 0))
                .putByte("Sticky", (byte) (sticky ? 1 : 0))
                .putByte("isMovable", (byte) 0)
                .putInt("x", position.getX())
                .putInt("y", position.getY())
                .putInt("z", position.getZ());
        return builder.build();
    }

    /**
     * Create a moving block tag of a block that will be moved by a piston
     * @param position The ending position of the block
     * @param movingBlock Block state data of the block that's moving
     * @param movingEntity Block entity data of the block if applicable
     * @param pistonPosition The position for the base of the piston that's moving the block
     * @return A moving block data tag
     */
    private NbtMap buildMovingBlockTag(Vector3i position, NbtMap movingBlock, NbtMap movingEntity, Vector3i pistonPosition) {
        NbtMapBuilder builder = NbtMap.builder()
                .putString("id", "MovingBlock")
                .putCompound("movingBlock", movingBlock)
                .putCompound("movingBlockExtra", AIR_TAG) //TODO figure out if this changes
                .putByte("isMovable", (byte) 1)
                .putInt("pistonPosX", pistonPosition.getX())
                .putInt("pistonPosY", pistonPosition.getY())
                .putInt("pistonPosZ", pistonPosition.getZ())
                .putInt("x", position.getX())
                .putInt("y", position.getY())
                .putInt("z", position.getZ());
        if (movingEntity != null) {
            builder.putCompound("movingEntity", movingEntity);
        }
        return builder.build();
    }
}
