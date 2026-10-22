package com.example.vampire;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.*;

public class VampireRoulette implements ModInitializer {

	// ===== إعدادات =====
	static final double BITE_DISTANCE = 3.0;           // اقصى مسافة للعض
	static final boolean REQUIRE_BLOCK_BETWEEN = true; // لازم يكون بينهم بلوكة
	static final int FREEZE_TICKS = 5 * 20;             // تثبيت العجوز 5 ثواني

	enum Role { NORMAL, VAMPIRE_PENDING, VAMPIRE, OLD_PENDING, OLD }

	static final Map<UUID, Role> ROLES = new HashMap<>();
	static final Set<UUID> WAS_SNEAKING = new HashSet<>();
	static boolean running = false;
	static int tickCounter = 0;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
			dispatcher.register(Commands.literal("vampire")
				.then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> start(ctx.getSource())))
				.then(Commands.literal("stop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> stop(ctx.getSource())))
				.then(Commands.literal("transform").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayer();
					return p == null ? 0 : doTransform(p, ctx.getSource().getServer());
				}))
				.then(Commands.literal("bite")
					.then(Commands.argument("target", StringArgumentType.word())
						.executes(ctx -> {
							ServerPlayer v = ctx.getSource().getPlayer();
							if (v == null) return 0;
							ServerPlayer t = ctx.getSource().getServer().getPlayerList()
								.getPlayerByName(StringArgumentType.getString(ctx, "target"));
							return t == null ? 0 : doBite(v, t, ctx.getSource().getServer());
						}))));
		});

		ServerTickEvents.END_SERVER_TICK.register(this::onTick);
	}

	// ===== بدء الروليت =====
	static int start(CommandSourceStack src) {
		MinecraftServer server = src.getServer();
		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
		int count = players.size();
		if (count < 2) {
			src.sendFailure(Component.literal("لازم لاعبين على الاقل. العدد الحالي: " + count));
			return 0;
		}
		ROLES.clear();
		WAS_SNEAKING.clear();
		running = true;

		Collections.shuffle(players);
		ServerPlayer vampire = players.get(0);
		ServerPlayer old = players.get(1);

		for (ServerPlayer p : players) ROLES.put(p.getUUID(), Role.NORMAL);
		ROLES.put(vampire.getUUID(), Role.VAMPIRE_PENDING);
		ROLES.put(old.getUUID(), Role.OLD_PENDING);

		server.getPlayerList().broadcastSystemMessage(
			Component.literal("🎲 بدأ الروليت! عدد اللاعبين: " + count).withStyle(ChatFormatting.GOLD), false);

		for (ServerPlayer p : players) {
			Role r = ROLES.get(p.getUUID());
			switch (r) {
				case VAMPIRE_PENDING -> p.sendSystemMessage(
					Component.literal("🧛 انت مصاص دماء! (سر) اضغط Shift عشان تتحول.").withStyle(ChatFormatting.DARK_RED));
				case OLD_PENDING -> p.sendSystemMessage(
					Component.literal("👴 انت العجوز! (سر) اضغط Shift عشان تتحول وتثبت الطبيعيين.").withStyle(ChatFormatting.AQUA));
				default -> p.sendSystemMessage(Component.literal("انت لاعب طبيعي.").withStyle(ChatFormatting.GREEN));
			}
		}
		return 1;
	}

	static int stop(CommandSourceStack src) {
		running = false;
		ROLES.clear();
		WAS_SNEAKING.clear();
		src.sendSuccess(() -> Component.literal("تم ايقاف الروليت."), true);
		return 1;
	}

	// ===== التحول =====
	static int doTransform(ServerPlayer p, MinecraftServer server) {
		if (!running) return 0;
		Role r = ROLES.get(p.getUUID());

		if (r == Role.VAMPIRE_PENDING) {
			ROLES.put(p.getUUID(), Role.VAMPIRE);
			makeVampireEffects(p);
			p.sendSystemMessage(Component.literal("🧛 تحولت الى مصاص دماء! قرب من لاعب وبينكم بلوكة، واضغط Shift عشان تعضه.")
				.withStyle(ChatFormatting.DARK_RED));
			return 1;
		}

		if (r == Role.OLD_PENDING) {
			ROLES.put(p.getUUID(), Role.OLD);
			p.sendSystemMessage(Component.literal("👴 تحولت! تم تثبيت الطبيعيين 5 ثواني.").withStyle(ChatFormatting.AQUA));
			for (ServerPlayer t : server.getPlayerList().getPlayers()) {
				if (ROLES.get(t.getUUID()) == Role.NORMAL) {
					freeze(t);
					t.sendSystemMessage(Component.literal("⛔ العجوز ثبتك!").withStyle(ChatFormatting.RED));
				}
			}
			return 1;
		}
		return 0;
	}

	static void freeze(ServerPlayer t) {
		t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, FREEZE_TICKS, 255, false, false));
		t.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, FREEZE_TICKS, 128, false, false));
	}

	static void makeVampireEffects(ServerPlayer p) {
		p.addEffect(new MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 0, false, false));
		p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
	}

	// ===== العض =====
	static int doBite(ServerPlayer v, ServerPlayer t, MinecraftServer server) {
		if (!running || ROLES.get(v.getUUID()) != Role.VAMPIRE) return 0;
		if (ROLES.get(t.getUUID()) != Role.NORMAL || !canBite(v, t)) return 0;

		ROLES.put(t.getUUID(), Role.VAMPIRE);
		t.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 200, 0));
		t.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
		t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
		makeVampireEffects(t);
		t.sendSystemMessage(Component.literal("🩸 انعضيت! صرت مصاص دماء مع الفريق.").withStyle(ChatFormatting.DARK_RED));
		v.sendSystemMessage(Component.literal("🧛 عضيت " + t.getName().getString() + " وصار معك!")
			.withStyle(ChatFormatting.RED));

		boolean anyNormal = ROLES.values().stream().anyMatch(r -> r == Role.NORMAL);
		if (!anyNormal) {
			server.getPlayerList().broadcastSystemMessage(
				Component.literal("🧛 انتهت اللعبة! تحول كل الطبيعيين.").withStyle(ChatFormatting.DARK_RED), false);
			running = false;
		}
		return 1;
	}

	static boolean canBite(ServerPlayer v, ServerPlayer t) {
		if (v.level() != t.level()) return false;
		if (v.distanceTo(t) > BITE_DISTANCE + 0.5) return false;
		if (!REQUIRE_BLOCK_BETWEEN) return true;
		var ctx = new ClipContext(v.getEyePosition(), t.getEyePosition(),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, v);
		return v.level().clip(ctx).getType() == HitResult.Type.BLOCK;
	}

	// ===== كل تيك: نص فوق الاكشن بار + ضغط Shift =====
	void onTick(MinecraftServer server) {
		tickCounter++;
		if (!running) return;

		List<ServerPlayer> all = server.getPlayerList().getPlayers();
		for (ServerPlayer p : all) {
			UUID id = p.getUUID();
			boolean sneaking = p.isShiftKeyDown();
			boolean pressed = sneaking && !WAS_SNEAKING.contains(id);
			if (sneaking) WAS_SNEAKING.add(id); else WAS_SNEAKING.remove(id);

			Role r = ROLES.get(id);
			if (r == null) continue;

			if (r == Role.VAMPIRE_PENDING || r == Role.OLD_PENDING) {
				if (tickCounter % 10 == 0) {
					p.displayClientMessage(Component.literal("⬆ اضغط Shift للتحول")
						.withStyle(r == Role.VAMPIRE_PENDING ? ChatFormatting.RED : ChatFormatting.AQUA), true);
				}
				if (pressed) doTransform(p, server);
				continue;
			}

			if (r == Role.VAMPIRE) {
				ServerPlayer target = null;
				for (ServerPlayer t : all) {
					if (t == p || ROLES.get(t.getUUID()) != Role.NORMAL) continue;
					if (canBite(p, t)) { target = t; break; }
				}
				if (target == null) continue;
				if (tickCounter % 10 == 0) {
					p.displayClientMessage(Component.literal("🧛 اضغط Shift لعض " + target.getName().getString())
						.withStyle(ChatFormatting.DARK_RED), true);
				}
				if (pressed) {
					doBite(p, target, server);
					if (!running) return;
				}
			}
		}
	}
}
