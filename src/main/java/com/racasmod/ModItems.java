package com.racasmod;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Itens dos personagens. Cada um é entregue ao escolher a raça (veja AnimeRaces.java)
 * e também aparece na aba Combate do modo criativo.
 * Texturas: assets/racas/textures/item/<id>.png | Nomes: assets/racas/lang/*.json
 */
public final class ModItems {
    private static final List<Item> ALL = new ArrayList<>();

    // ---- armas (dano, velocidade de ataque relativa: mais negativo = mais lento)
    /** A faca da Kobeni: textura toda estragada de propósito, mas a dona é uma assassina. */
    public static final Item KOBENI_KNIFE = weapon("faca_da_kobeni", 14.0, -1.6);
    public static final Item RIMURU_SWORD = weapon("espada_do_rimuru", 8.0, -1.2);
    public static final Item SHAULA_NEEDLE = weapon("agulha_da_shaula", 7.0, -1.0);
    public static final Item ODM_BLADE = weapon("lamina_3d", 8.0, -1.0);
    public static final Item NYOIBO = weapon("nyoibo", 10.0, -2.4);
    public static final Item SHADOW_BLADE = weapon("espada_slime_do_cid", 9.0, -1.6);
    public static final Item ELUCIDATOR = weapon("elucidator", 10.0, -1.6);
    public static final Item RHITTA = weapon("rhitta", 14.0, -3.0);
    public static final Item LOSTVAYNE = weapon("lostvayne", 10.0, -1.8);
    public static final Item DIO_KNIFE = weapon("faca_do_dio", 5.0, -0.6);
    public static final Item EA = weapon("ea", 11.0, -1.8);
    public static final Item DRAGONSLAYER = weapon("mata_dragao", 16.0, -3.2);
    public static final Item AINZ_STAFF = weapon("cajado_do_ainz", 3.0, -2.8);

    // ---- itens de personagem sem dano
    public static final Item DEATH_NOTE = plain("death_note");
    public static final Item SUBARU_PHONE = plain("celular_do_subaru");
    public static final Item SAITAMA_GLOVE = plain("luva_do_saitama");
    public static final Item SUKUNA_FINGER = plain("dedo_do_sukuna");
    public static final Item CASULL = plain("casull");

    private static Item reg(String id, Item item) {
        Registry.register(Registries.ITEM, Identifier.of(RacasMod.MOD_ID, id), item);
        ALL.add(item);
        return item;
    }

    private static Item weapon(String id, double damage, double speed) {
        return reg(id, new Item(new Item.Settings().maxCount(1).attributeModifiers(
                AttributeModifiersComponent.builder()
                        .add(EntityAttributes.GENERIC_ATTACK_DAMAGE,
                                new EntityAttributeModifier(Identifier.ofVanilla("base_attack_damage"), damage,
                                        EntityAttributeModifier.Operation.ADD_VALUE),
                                AttributeModifierSlot.MAINHAND)
                        .add(EntityAttributes.GENERIC_ATTACK_SPEED,
                                new EntityAttributeModifier(Identifier.ofVanilla("base_attack_speed"), speed,
                                        EntityAttributeModifier.Operation.ADD_VALUE),
                                AttributeModifierSlot.MAINHAND)
                        .build())));
    }

    private static Item plain(String id) {
        return reg(id, new Item(new Item.Settings().maxCount(1)));
    }

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> ALL.forEach(entries::add));
    }

    private ModItems() {}
}
