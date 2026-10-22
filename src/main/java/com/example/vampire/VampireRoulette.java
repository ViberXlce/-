package com.example.vampire;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
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
	static final double BITE_DISTANCE = 3.0;          // اقصى مسافة للعض
	static final boolean REQUIRE_BLOCK_BETWEEN = true; // لازم يكون بينهم بلوكة
	static final int FREEZE_TICKS = 5 * 20;            // تثبيت العجوز 5 ثواني
	static final int PROMPT_COOLDOWN = 60;             // كل كم تيك يطلع زر العض لنفس الشخص

	enum Role { NORMAL, VAMPIRE_PENDING, VAMPIRE, OLD_PENDING, OLD }

	static final Map<UUID, Role> ROLES = new HashMap<>();
	static final Map<String, Integer> PROMPTED = new HashMap<>(); // vampire:target -> tick
	static boolean running = false;
	static int tickCounter = 0;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
			dispatcher.register(Commands.literal("vampire")
				.then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> start(ctx.getSource())))
				.then(Commands.literal("stop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> stop(ctx.getSource())))
				.then(Commands.literal("transform").executes(ctx -> transform(ctx.getSource())))
				.then(Commands.literal("bite")
					.then(Commands.argument("target", StringArgumentType.word())
						.executes(ctx -> bite(ctx.getSource(), StringArgumentType.getString(ctx, "target"))))));
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
		PROMPTED.clear();
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
				case VAMPIRE_PENDING -> {
					p.sendSystemMessage(Component.literal("🧛 انت مصاص دماء! (سر) ").withStyle(ChatFormatting.DARK_RED)
						.append(button("[ تحول ]", "/vampire transform", ChatFormatting.RED)));
				}
				case OLD_PENDING -> {
					p.sendSystemMessage(Component.literal("👴 انت العجوز! (سر) ").withStyle(ChatFormatting.GRAY)
						.append(button("[ تحول ]", "/vampire transform", ChatFormatting.AQUA)));
				}
				default -> p.sendSystemMessage(Component.literal("انت لاعب طبيعي.").withStyle(ChatFormatting.GREEN));
			}
		}
		return 1;
	}

	static int stop(CommandSourceStack src) {
		running = false;
		ROLES.clear();
		PROMPTED.clear();
		src.sendSuccess(() -> Component.literal("تم ايقاف الروليت."), true);
		return 1;
	}

	// ===== التحول =====
	static int transform(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		if (p == null || !running) return 0;
		Role r = ROLES.get(p.getUUID());

		if (r == Role.VAMPIRE_PENDING) {
			ROLES.put(p.getUUID(), Role.VAMPIRE);
			makeVampireEffects(p);
			p.sendSystemMessage(Component.literal("🧛 تحولت الى مصاص دماء! قرب من لاعب وبينكم بلوكة عشان يطلع لك زر العض.")
				.withStyle(ChatFormatting.DARK_RED));
			return 1;
		}

		if (r == Role.OLD_PENDING) {
			ROLES.put(p.getUUID(), Role.OLD);
			p.sendSystemMessage(Component.literal("👴 تحولت! تم تثبيت الطبيعيين 5 ثواني.").withStyle(ChatFormatting.AQUA));
			for (ServerPlayer t : src.getServer().getPlayerList().getPlayers()) {
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
	static int bite(CommandSourceStack src, String targetName) {
		ServerPlayer v = src.getPlayer();
		if (v == null || !running || ROLES.get(v.getUUID()) != Role.VAMPIRE) return 0;

		ServerPlayer t = src.getServer().getPlayerList().getPlayerByName(targetName);
		if (t == null || ROLES.get(t.getUUID()) != Role.NORMAL) {
			v.sendSystemMessage(Component.literal("ما تقدر تعضه."));
			return 0;
		}
		if (!canBite(v, t)) {
			v.sendSystemMessage(Component.literal("بعيد او مو ضمن الشروط."));
			return 0;
		}

		ROLES.put(t.getUUID(), Role.VAMPIRE);
		t.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 200, 0));
		t.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
		t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
		makeVampireEffects(t);
		t.sendSystemMessage(Component.literal("🩸 انعضيت! صرت مصاص دماء مع الفريق.").withStyle(ChatFormatting.DARK_RED));
		v.sendSystemMessage(Component.literal("🧛 عضيت " + t.getGameProfile().name() + " وصار معك!")
			.withStyle(ChatFormatting.RED));

		boolean anyNormal = ROLES.values().stream().anyMatch(r -> r == Role.NORMAL);
		if (!anyNormal) {
			src.getServer().getPlayerList().broadcastSystemMessage(
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

	// ===== التيك: يطلع زر العض =====
	void onTick(MinecraftServer server) {
		tickCounter++;
		if (!running || tickCounter % 10 != 0) return;

		List<ServerPlayer> all = server.getPlayerList().getPlayers();
		for (ServerPlayer v : all) {
			if (ROLES.get(v.getUUID()) != Role.VAMPIRE) continue;
			for (ServerPlayer t : all) {
				if (ROLES.get(t.getUUID()) != Role.NORMAL) continue;
				if (!canBite(v, t)) continue;
				String key = v.getUUID() + ":" + t.getUUID();
				Integer last = PROMPTED.get(key);
				if (last != null && tickCounter - last < PROMPT_COOLDOWN) continue;
				PROMPTED.put(key, tickCounter);
				String name = t.getGameProfile().name();
				v.sendSystemMessage(Component.literal("قريب من " + name + " ")
					.append(button("[ عض ]", "/vampire bite " + name, ChatFormatting.RED)));
			}
		}
	}

	static Component button(String text, String command, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command)));
	}
}
