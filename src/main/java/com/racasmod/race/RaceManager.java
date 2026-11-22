package com.racasmod.race;

import com.racasmod.net.Payloads;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Toda a lógica de jogo das raças (servidor). */
public final class RaceManager {
    private static final Map<UUID, Map<Ability, Long>> cooldowns = new HashMap<>();
    private static final Map<UUID, Integer> selected = new HashMap<>();
    private static final Map<UUID, Long> counterUntil = new HashMap<>();
    private static final Map<UUID, Checkpoint> checkpoints = new HashMap<>();
    private static boolean reflecting = false;
    private static final Identifier TITAN_ID = Identifier.of("racas", "scale_titan");

    private record Checkpoint(RegistryKey<World> world, Vec3d pos) {}

    /** Tarefa agendada para alguns ticks no futuro (golpes em sequência, domínio etc.). */
    private static final class Task {
        int left;
        final Runnable run;
        Task(int left, Runnable run) { this.left = left; this.run = run; }
    }
    private static final List<Task> tasks = new ArrayList<>();
    private static final Map<UUID, Long> reviveCooldowns = new HashMap<>();
    private static final Set<UUID> allowChange = new HashSet<>();

    // ------------------------------------------------------------ consultas
    public static Race raceOf(ServerPlayerEntity p) {
        String id = RaceState.get(p.server).races.get(p.getUuid());
        return id == null ? null : Races.get(id);
    }

    public static Race mimicOf(ServerPlayerEntity p, Race r) {
        if (r == null || !r.mimic) return null;
        String id = RaceState.get(p.server).mimics.get(p.getUuid());
        return id == null ? null : Mimics.get(id);
    }

    public static void allowChange(UUID id) { allowChange.add(id); }

    // ------------------------------------------------------------ escolha
    public static void choose(ServerPlayerEntity p, String id) {
        Race r = Races.get(id);
        if (r == null) return;
        RaceState st = RaceState.get(p.server);
        boolean has = st.races.containsKey(p.getUuid());
        if (has && !allowChange.remove(p.getUuid())) {
            p.sendMessage(Text.literal("Você já escolheu sua raça!").formatted(Formatting.RED), true);
            return;
        }
        set(p, r);
    }

    public static void set(ServerPlayerEntity p, Race r) {
        RaceState st = RaceState.get(p.server);
        st.races.put(p.getUuid(), r.id);
        if (!r.mimic) st.mimics.remove(p.getUuid());
        st.markDirty();
        cooldowns.remove(p.getUuid());
        selected.remove(p.getUuid());
        applyAttributes(p);
        giveStarterItem(p);
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);

        p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(r.desc).formatted(Formatting.GRAY)));
        p.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(r.name).styled(s -> s.withColor(r.color).withBold(true))));
        p.server.getPlayerManager().broadcast(
                Text.literal(p.getName().getString() + " agora é ")
                        .append(Text.literal(r.name).styled(s -> s.withColor(r.color).withBold(true))), false);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1f, 1f);

        if (r.mimic) ServerPlayNetworking.send(p, new Payloads.OpenMenuPayload(1));
    }

    public static void chooseMimic(ServerPlayerEntity p, String id) {
        Race r = raceOf(p);
        Race m = Mimics.get(id);
        if (r == null || !r.mimic || m == null || m.mimic) return;
        RaceState st = RaceState.get(p.server);
        st.mimics.put(p.getUuid(), m.id);
        st.markDirty();
        applyAttributes(p);
        p.sendMessage(Text.literal("Você copiou o poder de: ")
                .append(Text.literal(m.name).styled(s -> s.withColor(m.color).withBold(true))), true);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 0.6f);
        p.getServerWorld().spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
    }

    // ------------------------------------------------------------ atributos
    public static void applyAttributes(ServerPlayerEntity p) {
        for (Attr a : Attr.values()) {
            EntityAttributeInstance inst = p.getAttributeInstance(a.entry);
            if (inst == null) continue;
            inst.removeModifier(a.id("r"));
            inst.removeModifier(a.id("m"));
        }
        EntityAttributeInstance sc = p.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        if (sc != null) sc.removeModifier(TITAN_ID); // some a forma de titã
        Race r = raceOf(p);
        if (r == null) return;
        addMods(p, r, "r");
        Race m = mimicOf(p, r);
        if (m != null) addMods(p, m, "m");
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void addMods(ServerPlayerEntity p, Race r, String src) {
        for (Race.AttrMod mod : r.attrs) {
            EntityAttributeInstance inst = p.getAttributeInstance(mod.attr().entry);
            if (inst == null) continue;
            inst.addTemporaryModifier(new EntityAttributeModifier(mod.attr().id(src), mod.value(), mod.attr().op));
        }
    }

    // ------------------------------------------------------------ tick
    public static void tick(ServerPlayerEntity p, int serverTicks) {
        if (serverTicks % 10 != 0 || !p.isAlive() || p.isSpectator()) return;
        Race r = raceOf(p);
        if (r == null) return;
        Race m = mimicOf(p, r);
        When.Ctx c = When.Ctx.of(p);

        if ((r.checkpoint || (m != null && m.checkpoint)) && serverTicks % 400 == 0
                && p.isOnGround() && !p.isOnFire() && p.getHealth() >= p.getMaxHealth() * 0.6f) {
            checkpoints.put(p.getUuid(), new Checkpoint(p.getServerWorld().getRegistryKey(), p.getPos()));
        }

        applyFx(p, r, c);
        if (m != null) applyFx(p, m, c);

        if (serverTicks % 20 == 0 && !p.isCreative()) {
            hurt(p, r, c);
            if (m != null) hurt(p, m, c);
            float hunger = r.hunger + (m != null ? m.hunger : 0f);
            if (hunger > 0f) p.addExhaustion(hunger);
        }
    }

    private static void applyFx(ServerPlayerEntity p, Race r, When.Ctx c) {
        for (Race.Fx f : r.fxs) {
            if (!f.when().test(c)) continue;
            int dur = f.effect() == StatusEffects.NIGHT_VISION ? 260 : 50;
            p.addStatusEffect(new StatusEffectInstance(f.effect(), dur, f.amp(), true, false, true));
        }
    }

    private static void hurt(ServerPlayerEntity p, Race r, When.Ctx c) {
        for (Race.Hurt h : r.hurts) {
            if (!h.when().test(c)) continue;
            if (h.fire()) p.setOnFireFor(3);
            else p.damage(p.getServerWorld().getDamageSources().generic(), h.amount());
        }
    }

    // ------------------------------------------------------------ eventos de combate
    public static void onDamageDealt(LivingEntity victim, ServerPlayerEntity attacker, float damage) {
        Race r = raceOf(attacker);
        if (r == null) return;
        Race m = mimicOf(attacker, r);
        float steal = r.lifesteal + (m != null ? m.lifesteal : 0f);
        if (steal > 0f && damage > 0f) attacker.heal(damage * steal);
    }

    /** Volta da morte (Subaru, Rimuru, Alucard). Retorna true se reviveu. */
    public static boolean tryRevive(ServerPlayerEntity p) {
        Race r = raceOf(p);
        if (r == null) return false;
        Race m = mimicOf(p, r);
        Race src = r.revive ? r : (m != null && m.revive ? m : null);
        if (src == null) return false;
        ServerWorld w = p.getServerWorld();
        long now = w.getTime();
        Long ready = reviveCooldowns.get(p.getUuid());
        if (ready != null && ready > now) return false;
        reviveCooldowns.put(p.getUuid(), now + src.reviveTicks);
        p.setHealth(Math.min(src.reviveHealth, p.getMaxHealth()));
        p.extinguish();
        p.fallDistance = 0;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 2));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 200, 0));
        String msg = "Você renasceu das cinzas!";
        if (src.checkpoint) {
            Checkpoint cp = checkpoints.get(p.getUuid());
            if (cp != null && cp.world().equals(w.getRegistryKey())) {
                p.requestTeleport(cp.pos().x, cp.pos().y, cp.pos().z);
            }
            msg = "Retorno pela Morte!";
        }
        w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 80, 0.6, 1.0, 0.6, 0.1);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1f, 1f);
        p.sendMessage(Text.literal(msg).formatted(Formatting.GOLD), true);
        return true;
    }

    /** Dano recebido: Contra-Ataque Total (Meliodas) e esquiva (Kobeni). Retorna false para cancelar o dano. */
    public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (reflecting || !(entity instanceof ServerPlayerEntity p)) return true;
        if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        Race r = raceOf(p);
        if (r == null) return true;
        Race m = mimicOf(p, r);
        ServerWorld w = p.getServerWorld();

        Long until = counterUntil.get(p.getUuid());
        if (until != null && until > w.getTime() && source.getAttacker() instanceof LivingEntity att && att != p) {
            reflecting = true;
            try {
                att.damage(w.getDamageSources().playerAttack(p), Math.max(amount, 1f) * 2f);
            } finally {
                reflecting = false;
            }
            w.spawnParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1, p.getZ(), 25, 0.5, 0.8, 0.5, 0.2);
            return false;
        }

        float dodge = r.dodge + (m != null ? m.dodge : 0f);
        if (dodge > 0f && p.getRandom().nextFloat() < dodge) {
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 12, 0.4, 0.6, 0.4, 0.05);
            p.sendMessage(Text.literal("Desviou!").formatted(Formatting.LIGHT_PURPLE), true);
            return false;
        }
        return true;
    }

    /** Executa as tarefas agendadas (chamado todo tick do servidor). */
    public static void runTasks() {
        if (tasks.isEmpty()) return;
        List<Runnable> ready = new ArrayList<>();
        Iterator<Task> it = tasks.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (--t.left <= 0) {
                ready.add(t.run);
                it.remove();
            }
        }
        for (Runnable r : ready) {
            try { r.run(); } catch (RuntimeException ignored) { }
        }
    }

    private static void later(int ticks, Runnable r) {
        tasks.add(new Task(Math.max(1, ticks), r));
    }

    /** Entrega o item inicial da raça (ex.: a faca da Kobeni) se o jogador ainda não tiver. */
    public static void giveStarterItem(ServerPlayerEntity p) {
        Race r = raceOf(p);
        if (r == null || r.giveItem == null) return;
        Item item = r.giveItem;
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).isOf(item)) return;
        }
        p.giveItemStack(new net.minecraft.item.ItemStack(item));
    }

    // ------------------------------------------------------------ habilidades
    public static void action(ServerPlayerEntity p, int action) {
        Race r = raceOf(p);
        if (r == null) return;
        if (action == 1) {
            if (r.mimic) ServerPlayNetworking.send(p, new Payloads.OpenMenuPayload(1));
            else p.sendMessage(Text.literal("Só o Herobrine copia poderes!").formatted(Formatting.RED), true);
            return;
        }
        useAbility(p, r);
    }

    private static void useAbility(ServerPlayerEntity p, Race r) {
        Race m = mimicOf(p, r);
        List<Ability> list = (m != null && m.ability != Ability.NONE) ? m.abilities() : r.abilities();
        if (list.isEmpty()) {
            p.sendMessage(Text.literal("Sua raça não tem habilidade ativa.").formatted(Formatting.GRAY), true);
            return;
        }
        // Agachado + R: troca a habilidade selecionada
        if (p.isSneaking() && list.size() > 1) {
            int next = (selected.getOrDefault(p.getUuid(), 0) + 1) % list.size();
            selected.put(p.getUuid(), next);
            Ability n = list.get(next);
            p.sendMessage(Text.literal("Habilidade: " + n.title + " (" + n.cooldownSeconds + "s)")
                    .formatted(Formatting.AQUA), true);
            return;
        }
        Ability a = list.get(Math.min(selected.getOrDefault(p.getUuid(), 0), list.size() - 1));
        long now = p.getServerWorld().getTime();
        Map<Ability, Long> cd = cooldowns.computeIfAbsent(p.getUuid(), k -> new EnumMap<>(Ability.class));
        Long ready = cd.get(a);
        if (ready != null && ready > now) {
            long left = (ready - now + 19) / 20;
            p.sendMessage(Text.literal(a.title + ": recarregando... " + left + "s").formatted(Formatting.RED), true);
            return;
        }
        cd.put(a, now + a.cooldownSeconds * 20L);
        if (!run(p, a)) {
            cd.remove(a); // errou o alvo: não gasta a recarga
            p.sendMessage(Text.literal(a.title + ": nenhum alvo!").formatted(Formatting.GRAY), true);
            return;
        }
        p.sendMessage(Text.literal(a.title + "!").formatted(Formatting.GOLD), true);
    }

    /** Executa a habilidade. Retorna false se não havia alvo (a recarga não é gasta). */
    private static boolean run(ServerPlayerEntity p, Ability a) {
        ServerWorld w = p.getServerWorld();
        Vec3d look = p.getRotationVec(1f);

        switch (a) {
            // ================================================== habilidades gerais
            case DASH -> {
                p.setVelocity(look.x * 1.7, 0.25, look.z * 1.7);
                p.velocityModified = true;
                p.fallDistance = 0;
                w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 20, 0.3, 0.3, 0.3, 0.05);
                sound(w, p, SoundEvents.ENTITY_BAT_TAKEOFF, 1f);
            }
            case VANISH -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 160, 0));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 160, 1));
                poof(w, p.getPos());
            }
            case BARRIER -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 120, 2));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 120, 2));
                w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.6, 0.9, 0.6, 0.02);
                sound(w, p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.2f);
            }

            // ================================================== Subaru
            case MAO_INVISIVEL -> {
                for (LivingEntity e : targets(p, 7)) {
                    strike(p, e, 12f, true);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 2));
                    w.spawnParticles(ParticleTypes.SOUL, e.getX(), e.getY() + 1, e.getZ(), 15, 0.3, 0.6, 0.3, 0.05);
                }
                w.spawnParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1, p.getZ(), 40, 3, 0.8, 3, 0.02);
                sound(w, p, SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1f);
            }

            // ================================================== Cid
            case ATOMIC -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 4, true, false, false));
                for (LivingEntity e : targets(p, 14)) {
                    float f = 1f - (float) (e.distanceTo(p) / 16.0);
                    strike(p, e, 15f + 40f * f, true);
                    shove(e, p.getPos(), 2.0, 0.8);
                }
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1, p.getZ(), 6, 4, 1, 4, 0);
                w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 150, 6, 3, 6, 0.3);
                sound(w, p, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.6f);
            }

            // ================================================== Goku
            case KAMEHAMEHA -> {
                beam(p, look, 30, 1.6, 30f, ParticleTypes.END_ROD, true, 1.2);
                beam(p, look, 30, 0.8, 0f, ParticleTypes.SOUL_FIRE_FLAME, true, 0);
                sound(w, p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.8f);
            }
            case SUPER_SAIYAJIN -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 3));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600, 2));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 600, 1));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 600, 1));
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 80, 0.6, 1, 0.6, 0.3);
                w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.9, 0.5, 0.1);
                sound(w, p, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 1.4f);
            }

            // ================================================== Saitama
            case SERIOUS_PUNCH -> {
                beam(p, look, 25, 3.0, 150f, ParticleTypes.EXPLOSION, false, 3.0);
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX() + look.x * 3, p.getY() + 1, p.getZ() + look.z * 3, 3, 0.5, 0.5, 0.5, 0);
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 0.5f);
            }

            // ================================================== Sukuna
            case DISMANTLE -> {
                beam(p, look, 20, 1.2, 12f, ParticleTypes.SWEEP_ATTACK, false, 0.3);
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f);
            }
            case CLEAVE -> {
                LivingEntity t = nearestInCone(p, 8, 0.85);
                if (t == null) return false;
                strike(p, t, Math.min(200f, Math.max(12f, t.getMaxHealth() * 0.4f)), false);
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY() + 1, t.getZ(), 5, 0.4, 0.5, 0.4, 0);
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1f);
            }
            case FUGA -> {
                Vec3d c = p.raycast(40, 1f, false).getPos();
                Vec3d from = p.getEyePos();
                Vec3d step = c.subtract(from);
                int n = Math.max(4, (int) (step.length() * 2));
                for (int i = 0; i < n; i++) {
                    Vec3d pt = from.add(step.multiply(i / (double) n));
                    w.spawnParticles(ParticleTypes.FLAME, pt.x, pt.y, pt.z, 3, 0.15, 0.15, 0.15, 0.01);
                }
                for (LivingEntity e : around(p, c, 7)) {
                    strike(p, e, 50f, true);
                    e.setOnFireFor(10);
                    shove(e, c, 1.2, 0.6);
                }
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 4, 2, 1, 2, 0);
                w.spawnParticles(ParticleTypes.FLAME, c.x, c.y + 1, c.z, 200, 3, 2, 3, 0.15);
                sound(w, p, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.7f);
            }
            case MALEVOLENT_SHRINE -> {
                final Vec3d center = p.getPos();
                for (int i = 0; i < 30; i++) {
                    later(i * 4, () -> {
                        for (LivingEntity e : around(p, center, 16)) {
                            strike(p, e, 3f, false);
                            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + 1, e.getZ(), 2, 0.4, 0.5, 0.4, 0);
                        }
                        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y + 1, center.z, 25, 8, 2, 8, 0);
                        w.spawnParticles(ParticleTypes.CRIMSON_SPORE, center.x, center.y + 1, center.z, 25, 8, 2, 8, 0);
                    });
                }
                sound(w, p, SoundEvents.ENTITY_WITHER_SPAWN, 0.8f);
            }

            // ================================================== Shaula
            case HELLS_SNIPE -> {
                LivingEntity t = nearestInCone(p, 40, 0.98);
                if (t == null) return false;
                strike(p, t, 14f, true);
                Vec3d from = p.getEyePos();
                Vec3d to = t.getPos().add(0, t.getHeight() / 2, 0);
                Vec3d step = to.subtract(from);
                int n = Math.max(4, (int) (step.length() * 2));
                for (int i = 0; i < n; i++) {
                    Vec3d pt = from.add(step.multiply(i / (double) n));
                    w.spawnParticles(ParticleTypes.END_ROD, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
                }
                sound(w, p, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1.5f);
            }
            case INFINITE_SNIPE -> {
                for (LivingEntity e : targets(p, 24)) {
                    strike(p, e, 20f, true);
                    for (int i = 0; i < 6; i++) {
                        w.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1 + i, e.getZ(), 4, 0.2, 0.2, 0.2, 0.05);
                    }
                }
                w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 3, p.getZ(), 60, 6, 1, 6, 0.1);
                sound(w, p, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f);
            }

            // ================================================== Eren
            case TITAN_FORM -> {
                if (p.getHealth() > 6f) p.damage(w.getDamageSources().generic(), 4f); // morde a mão
                EntityAttributeInstance sc = p.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
                if (sc != null && sc.getModifier(TITAN_ID) == null) {
                    sc.addTemporaryModifier(new EntityAttributeModifier(TITAN_ID, 2.5,
                            EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                }
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 4));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 600, 2));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 600, 4));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 600, 1));
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1, p.getZ(), 2, 0.5, 0.5, 0.5, 0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 60, 0.6, 1, 0.6, 0.4);
                sound(w, p, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1f);
                later(600, () -> {
                    EntityAttributeInstance s2 = p.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
                    if (s2 != null) s2.removeModifier(TITAN_ID);
                });
            }
            case RUMBLING -> {
                for (int i = 0; i < 8; i++) {
                    later(i * 8, () -> {
                        for (LivingEntity e : targets(p, 20)) {
                            strike(p, e, 8f, false);
                            shove(e, p.getPos(), 0.4, 0.6);
                        }
                        w.spawnParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.2, p.getZ(), 15, 10, 0.2, 10, 0);
                        sound(w, p, SoundEvents.BLOCK_ANVIL_LAND, 0.5f);
                    });
                }
            }

            // ================================================== Light
            case DEATH_NOTE -> {
                LivingEntity t = nearestInCone(p, 40, 0.97);
                if (t == null) return false;
                t.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 40, 0));
                w.spawnParticles(ParticleTypes.SOUL, t.getX(), t.getY() + 1, t.getZ(), 20, 0.3, 0.6, 0.3, 0.03);
                sound(w, p, SoundEvents.ITEM_BOOK_PAGE_TURN, 0.8f);
                later(800, () -> { // 40 segundos: ataque cardíaco
                    if (!t.isAlive()) return;
                    strike(p, t, 1000f, true);
                    w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, t.getX(), t.getY() + 1, t.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
                });
            }

            // ================================================== Anos
            case DESTRUCTION_EYE -> {
                LivingEntity t = nearestInCone(p, 30, 0.985);
                if (t == null) return false;
                strike(p, t, 1000f, true);
                w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, t.getX(), t.getY() + 1, t.getZ(), 60, 0.4, 0.8, 0.4, 0.1);
                w.spawnParticles(ParticleTypes.FLASH, t.getX(), t.getY() + 1, t.getZ(), 1, 0, 0, 0, 0);
                sound(w, p, SoundEvents.ENTITY_WITHER_SHOOT, 0.6f);
            }

            // ================================================== Ainz
            case GRASP_HEART -> {
                boolean any = false;
                for (LivingEntity e : targets(p, 12)) {
                    any = true;
                    if (e.getMaxHealth() <= 40f) strike(p, e, 1000f, true);
                    else strike(p, e, e.getMaxHealth() * 0.3f, true);
                    w.spawnParticles(ParticleTypes.SOUL, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.6, 0.3, 0.05);
                }
                if (!any) return false;
                sound(w, p, SoundEvents.ENTITY_WITHER_SPAWN, 1.3f);
            }

            // ================================================== DIO / tempo
            case TIME_STOP -> {
                for (LivingEntity e : targets(p, 24)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 9));
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 4));
                    e.setVelocity(Vec3d.ZERO);
                    e.velocityModified = true;
                    if (e instanceof MobEntity m && !m.isAiDisabled()) {
                        m.setAiDisabled(true);
                        later(100, () -> m.setAiDisabled(false));
                    }
                }
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 80, 8, 2, 8, 0.05);
                sound(w, p, SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.5f);
            }
            case KNIVES -> {
                for (int off = -20; off <= 20; off += 10) {
                    beam(p, look.rotateY((float) Math.toRadians(off)), 16, 0.8, 5f, ParticleTypes.CRIT, false, 0.3);
                }
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f);
            }
            case ROAD_ROLLER -> {
                p.setVelocity(0, 1.6, 0);
                p.velocityModified = true;
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 80, 4, true, false, false));
                later(16, () -> {
                    p.setVelocity(0, -3.0, 0);
                    p.velocityModified = true;
                });
                later(26, () -> {
                    p.fallDistance = 0;
                    for (LivingEntity e : targets(p, 6)) {
                        strike(p, e, 20f, false);
                        shove(e, p.getPos(), 0.8, 0.7);
                    }
                    w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 0.3, p.getZ(), 3, 1.5, 0.1, 1.5, 0);
                    sound(w, p, SoundEvents.BLOCK_ANVIL_LAND, 0.6f);
                });
            }

            // ================================================== Kirito
            case STARBURST -> {
                LivingEntity t = nearestInCone(p, 8, 0.8);
                if (t == null) return false;
                Vec3d toward = t.getPos().subtract(p.getPos());
                if (toward.length() > 2.5) {
                    Vec3d dir = toward.normalize();
                    p.setVelocity(dir.x * 0.9, 0.1, dir.z * 0.9);
                    p.velocityModified = true;
                }
                for (int i = 0; i < 16; i++) {
                    later(i * 2 + 1, () -> {
                        if (!t.isAlive()) return;
                        strike(p, t, 4f, false);
                        w.spawnParticles(ParticleTypes.CRIT, t.getX(), t.getY() + 1, t.getZ(), 6, 0.3, 0.5, 0.3, 0.2);
                        sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f);
                    });
                }
            }

            // ================================================== Escanor
            case CRUEL_SUN -> {
                Vec3d c = p.raycast(14, 1f, false).getPos();
                for (LivingEntity e : around(p, c, 8)) {
                    strike(p, e, 28f, true);
                    e.setOnFireFor(8);
                }
                w.spawnParticles(ParticleTypes.FLAME, c.x, c.y + 2, c.z, 200, 3, 2, 3, 0.1);
                w.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 2, c.z, 60, 3, 2, 3, 0);
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 3, 2, 1, 2, 0);
                sound(w, p, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.8f);
            }
            case DIVINE_AXE -> {
                beam(p, look, 20, 3.0, 50f, ParticleTypes.SWEEP_ATTACK, false, 2.0);
                beam(p, look, 20, 1.5, 0f, ParticleTypes.FLAME, false, 0);
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 0.6f);
            }

            // ================================================== Meliodas
            case FULL_COUNTER -> {
                counterUntil.put(p.getUuid(), w.getTime() + 80);
                w.spawnParticles(ParticleTypes.ENCHANTED_HIT, p.getX(), p.getY() + 1, p.getZ(), 40, 0.6, 0.9, 0.6, 0.2);
                sound(w, p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f);
            }
            case HELLBLAZE -> {
                for (LivingEntity e : targets(p, 8)) {
                    strike(p, e, 8f, true);
                    e.setOnFireFor(8);
                }
                w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1, p.getZ(), 150, 4, 1, 4, 0.1);
                sound(w, p, SoundEvents.ENTITY_BLAZE_SHOOT, 0.7f);
            }

            // ================================================== Alucard
            case HELLHOUNDS -> {
                List<LivingEntity> list = targets(p, 15);
                if (!list.isEmpty()) {
                    for (LivingEntity e : list) {
                        for (int i = 0; i < 3; i++) {
                            double ang = i * (Math.PI * 2 / 3);
                            fang(w, p, e.getX() + Math.cos(ang) * 1.2, e.getY(), e.getZ() + Math.sin(ang) * 1.2, (float) ang, i * 5);
                        }
                    }
                } else {
                    Vec3d flat = new Vec3d(look.x, 0, look.z).normalize();
                    for (int i = 0; i < 10; i++) {
                        Vec3d pt = p.getPos().add(flat.multiply(2 + i * 1.4));
                        fang(w, p, pt.x, pt.y, pt.z, (float) Math.atan2(flat.z, flat.x), i * 2);
                    }
                }
                w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1, p.getZ(), 40, 1, 0.5, 1, 0.05);
                sound(w, p, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 0.8f);
            }

            case CROMWELL -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 400, 3));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 400, 2));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 400, 3));
                w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1, p.getZ(), 80, 0.6, 1, 0.6, 0.1);
                w.spawnParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1, p.getZ(), 60, 1, 1, 1, 0.05);
                sound(w, p, SoundEvents.ENTITY_WITHER_SPAWN, 0.7f);
            }

            // ================================================== Kobeni
            case PANIC_RUN -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 160, 5));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 160, 1));
                w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 25, 0.3, 0.1, 0.3, 0.05);
                sound(w, p, SoundEvents.ENTITY_BAT_TAKEOFF, 1.4f);
            }
            case DESPERATE_STAB -> {
                LivingEntity t = nearestInCone(p, 4.5, 0.8);
                if (t == null) return false;
                float roll = p.getRandom().nextFloat();
                final boolean kill = roll < 0.20f;
                float dmg = kill ? 250f : 28f + p.getRandom().nextInt(8);
                final String msg = kill ? "Era tudo fingimento..." : "Ponto fatal!";
                strike(p, t, dmg, false);
                w.spawnParticles(ParticleTypes.CRIT, t.getX(), t.getY() + 1, t.getZ(), 15, 0.3, 0.5, 0.3, 0.2);
                later(2, () -> p.sendMessage(Text.literal(msg).formatted(Formatting.GOLD), true));
            }

            // ================================================== Gilgamesh
            case GATE_OF_BABYLON -> {
                List<LivingEntity> list = new ArrayList<>();
                for (LivingEntity e : targets(p, 24)) if (inCone(p, e, 0.3)) list.add(e);
                if (list.isEmpty()) return false;
                for (int v = 0; v < 6; v++) {
                    later(v * 5, () -> {
                        for (LivingEntity e : list) {
                            if (!e.isAlive()) continue;
                            strike(p, e, 6f, false);
                            for (int i = 0; i < 5; i++) {
                                w.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1 + i, e.getZ(), 3, 0.15, 0.15, 0.15, 0);
                            }
                        }
                        w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2.5, p.getZ(), 15, 1.5, 0.5, 1.5, 0.02);
                        sound(w, p, SoundEvents.ENTITY_ARROW_SHOOT, 1.2f);
                    });
                }
            }
            case ENKIDU -> {
                List<LivingEntity> list = targets(p, 16);
                if (list.isEmpty()) return false;
                for (LivingEntity e : list) {
                    pull(e, p.getPos(), 1.0);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 6));
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 1));
                    w.spawnParticles(ParticleTypes.ENCHANTED_HIT, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.6, 0.3, 0.1);
                }
                sound(w, p, SoundEvents.BLOCK_CHAIN_PLACE, 1f);
            }
            case ENUMA_ELISH -> {
                beam(p, look, 40, 4.0, 120f, ParticleTypes.FLAME, true, 2.5);
                beam(p, look, 40, 2.0, 0f, ParticleTypes.END_ROD, true, 0);
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX() + look.x * 3, p.getY() + 1, p.getZ() + look.z * 3, 2, 0.5, 0.5, 0.5, 0);
                sound(w, p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.5f);
            }

            // ================================================== Guts
            case DRAGON_SLAYER -> {
                LivingEntity t = nearestInCone(p, 5, 0.85);
                if (t == null) return false;
                strike(p, t, 40f, false);
                shove(t, p.getPos(), 1.5, 0.4);
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY() + 1, t.getZ(), 4, 0.4, 0.5, 0.4, 0);
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 0.6f);
            }
            case BERSERKER_ARMOR -> {
                if (p.getHealth() > 8f) p.damage(w.getDamageSources().generic(), 5f); // o corpo paga o preço
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 400, 2));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 400, 2));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 400, 0));
                w.spawnParticles(ParticleTypes.SMOKE, p.getX(), p.getY() + 1, p.getZ(), 50, 0.5, 0.9, 0.5, 0.05);
                sound(w, p, SoundEvents.ENTITY_RAVAGER_ROAR, 1f);
            }
            case ARM_CANNON -> {
                Vec3d c = p.raycast(25, 1f, false).getPos();
                for (LivingEntity e : around(p, c, 3.5)) {
                    strike(p, e, 20f, false);
                    shove(e, c, 1.0, 0.5);
                }
                Vec3d from = p.getEyePos();
                Vec3d step = c.subtract(from);
                int n = Math.max(4, (int) (step.length() * 2));
                for (int i = 0; i < n; i++) {
                    Vec3d pt = from.add(step.multiply(i / (double) n));
                    w.spawnParticles(ParticleTypes.SMOKE, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
                }
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 2, 0.5, 0.5, 0.5, 0);
                sound(w, p, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 1f);
            }

            // ================================================== Rimuru
            case PREDATOR -> {
                LivingEntity t = nearestInCone(p, 6, 0.85);
                if (t == null) return false;
                strike(p, t, 20f, false);
                p.heal(6f);
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 600, 1));
                w.spawnParticles(ParticleTypes.SQUID_INK, t.getX(), t.getY() + 1, t.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
                w.spawnParticles(ParticleTypes.ITEM_SLIME, t.getX(), t.getY() + 1, t.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
                sound(w, p, SoundEvents.ENTITY_GENERIC_EAT, 0.8f);
            }
            case WATER_BLADE -> {
                beam(p, look, 14, 1.0, 10f, ParticleTypes.SPLASH, false, 0.3);
                beam(p, look, 14, 0.5, 0f, ParticleTypes.SWEEP_ATTACK, false, 0);
                sound(w, p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f);
            }
            case STICKY_THREAD -> {
                boolean any = false;
                for (LivingEntity e : targets(p, 12)) {
                    if (!inCone(p, e, 0.7)) continue;
                    any = true;
                    strike(p, e, 4f, false);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 6));
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 1));
                    w.spawnParticles(ParticleTypes.CRIT, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
                }
                if (!any) return false;
                sound(w, p, SoundEvents.BLOCK_CHAIN_PLACE, 1.2f);
            }
            case BLACK_LIGHTNING -> {
                List<LivingEntity> list = new ArrayList<>();
                for (LivingEntity e : targets(p, 28)) if (inCone(p, e, 0.7)) list.add(e);
                list.sort((x, y) -> Double.compare(x.squaredDistanceTo(p), y.squaredDistanceTo(p)));
                if (list.isEmpty()) {
                    bolt(w, p.raycast(30, 1f, false).getPos());
                } else {
                    for (int i = 0; i < Math.min(3, list.size()); i++) bolt(w, list.get(i).getPos());
                }
            }
            case HELL_FLARE -> {
                Vec3d c = p.raycast(16, 1f, false).getPos();
                for (LivingEntity e : around(p, c, 6)) {
                    strike(p, e, 22f, true);
                    e.setOnFireFor(8);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
                }
                w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 1.5, c.z, 200, 2.5, 1.5, 2.5, 0.1);
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 2, 1.5, 0.5, 1.5, 0);
                sound(w, p, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.7f);
            }
            case GRAVITY_COLLAPSE -> {
                Vec3d c = p.raycast(14, 1f, false).getPos();
                List<LivingEntity> list = around(p, c, 10);
                if (list.isEmpty()) return false;
                for (LivingEntity e : list) {
                    pull(e, c, 1.2);
                    strike(p, e, 15f, true);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2));
                }
                w.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 1, c.z, 120, 3, 1.5, 3, 0.5);
                sound(w, p, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 0.5f);
            }
            case BEELZEBUTH -> {
                List<LivingEntity> list = targets(p, 12);
                if (list.isEmpty()) return false;
                float healed = 0;
                for (LivingEntity e : list) {
                    strike(p, e, 18f, true);
                    healed += 2f;
                    w.spawnParticles(ParticleTypes.SOUL, e.getX(), e.getY() + 1, e.getZ(), 15, 0.3, 0.6, 0.3, 0.1);
                }
                p.heal(Math.min(healed, 20f));
                w.spawnParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1, p.getZ(), 80, 5, 1.5, 5, 0.05);
                sound(w, p, SoundEvents.ENTITY_WITHER_SPAWN, 1.2f);
            }

            default -> { return false; }
        }
        return true;
    }

    // ------------------------------------------------------------ auxiliares

    /** Pode ser alvo de uma habilidade? (não é você, não é aliado nem jogador criativo, e respeita PvP) */
    private static boolean valid(ServerPlayerEntity p, LivingEntity e) {
        if (e == p || !e.isAlive() || e.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof TameableEntity t && t.isOwner(p)) return false;
        if (e instanceof PlayerEntity other) {
            return !other.isCreative() && p.server.isPvpEnabled();
        }
        return true;
    }

    /** Alvos num raio ao redor do jogador. */
    private static List<LivingEntity> targets(ServerPlayerEntity p, double radius) {
        return around(p, p.getPos(), radius);
    }

    /** Alvos num raio ao redor de um ponto qualquer. */
    private static List<LivingEntity> around(ServerPlayerEntity p, Vec3d c, double radius) {
        Box box = new Box(c, c).expand(radius);
        double r2 = radius * radius;
        return p.getServerWorld().getEntitiesByClass(LivingEntity.class, box,
                e -> valid(p, e) && e.squaredDistanceTo(c) <= r2);
    }

    /** O alvo está dentro do cone de visão do jogador? (minDot: 1.0 = exatamente no centro) */
    private static boolean inCone(ServerPlayerEntity p, LivingEntity e, double minDot) {
        Vec3d to = e.getPos().add(0, e.getHeight() / 2, 0).subtract(p.getEyePos());
        if (to.lengthSquared() < 1.0E-4) return true;
        return to.normalize().dotProduct(p.getRotationVec(1f)) >= minDot;
    }

    /** O alvo mais próximo dentro do cone de visão, ou null. */
    private static LivingEntity nearestInCone(ServerPlayerEntity p, double range, double minDot) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : targets(p, range)) {
            if (!inCone(p, e, minDot)) continue;
            double d = e.squaredDistanceTo(p);
            if (d < bestDist) { best = e; bestDist = d; }
        }
        return best;
    }

    /** Causa dano ignorando o tempo de invulnerabilidade (para golpes em sequência). */
    private static void strike(ServerPlayerEntity p, LivingEntity e, float amount, boolean magic) {
        if (amount <= 0f) return;
        ServerWorld w = p.getServerWorld();
        e.timeUntilRegen = 0;
        DamageSource src = magic ? w.getDamageSources().indirectMagic(p, p) : w.getDamageSources().playerAttack(p);
        e.damage(src, amount);
    }

    /**
     * Raio em linha reta: partículas e dano em tudo no caminho.
     * damage = 0 só desenha as partículas. kb = força do empurrão nos atingidos.
     */
    private static void beam(ServerPlayerEntity p, Vec3d dir, double range, double width, float damage,
                             ParticleEffect fx, boolean magic, double kb) {
        ServerWorld w = p.getServerWorld();
        Vec3d start = p.getEyePos().add(0, -0.3, 0);
        Set<LivingEntity> hit = new HashSet<>();
        for (double d = 1.0; d <= range; d += 0.75) {
            Vec3d pt = start.add(dir.multiply(d));
            w.spawnParticles(fx, pt.x, pt.y, pt.z, 2, width * 0.2, width * 0.2, width * 0.2, 0.0);
            if (damage > 0f) hit.addAll(around(p, pt, width));
        }
        for (LivingEntity e : hit) {
            strike(p, e, damage, magic);
            if (kb > 0) shove(e, p.getPos(), kb, 0.4);
        }
    }

    /** Empurra para longe de um ponto. */
    private static void shove(Entity e, Vec3d from, double horizontal, double up) {
        Vec3d d = new Vec3d(e.getX() - from.x, 0, e.getZ() - from.z);
        d = d.lengthSquared() < 1.0E-4 ? Vec3d.ZERO : d.normalize().multiply(horizontal);
        e.setVelocity(d.x, up, d.z);
        e.velocityModified = true;
    }

    /** Puxa em direção a um ponto. */
    private static void pull(Entity e, Vec3d to, double strength) {
        Vec3d d = new Vec3d(to.x - e.getX(), 0, to.z - e.getZ());
        d = d.lengthSquared() < 1.0E-4 ? Vec3d.ZERO : d.normalize().multiply(strength);
        e.setVelocity(d.x, 0.25, d.z);
        e.velocityModified = true;
    }

    private static void bolt(ServerWorld w, Vec3d pos) {
        LightningEntity b = EntityType.LIGHTNING_BOLT.create(w);
        if (b != null) {
            b.refreshPositionAfterTeleport(pos.x, pos.y, pos.z);
            w.spawnEntity(b);
        }
    }

    private static void fang(ServerWorld w, ServerPlayerEntity owner, double x, double y, double z, float yaw, int warmup) {
        w.spawnEntity(new EvokerFangsEntity(w, x, y, z, yaw, warmup, owner));
    }

    private static void poof(ServerWorld w, Vec3d pos) {
        w.spawnParticles(ParticleTypes.PORTAL, pos.x, pos.y + 1, pos.z, 30, 0.4, 0.8, 0.4, 0.2);
    }

    private static void sound(ServerWorld w, ServerPlayerEntity p, SoundEvent ev, float pitch) {
        w.playSound(null, p.getBlockPos(), ev, SoundCategory.PLAYERS, 1f, pitch);
    }
}
