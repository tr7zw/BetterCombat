package net.bettercombat.attack;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import net.bettercombat.api.AttributesContainer;
import net.bettercombat.api.WeaponAttributes;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.*;

public class WeaponRegistry {
    static Map<Identifier, WeaponAttributes> registrations = new HashMap();

    public static void register(Identifier itemId, WeaponAttributes attributes) {
        registrations.put(itemId, attributes);
    }

    public static WeaponAttributes getAttributes(Identifier itemId) {
        return registrations.get(itemId);
    }

    public static WeaponAttributes getAttributes(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        Item item = itemStack.getItem();
        Identifier id = Registry.ITEM.getId(item);
        WeaponAttributes attributes = WeaponRegistry.getAttributes(id);
        return attributes;
    }

    // LOADING

    public static void loadAttributes(ResourceManager resourceManager) {
        var gson = new Gson();
        Type fileFormat = new TypeToken<AttributesContainer>() {}.getType();
        Map<Identifier, AttributesContainer> containers = new HashMap();
        // Reading all attribute files
        for (Identifier identifier : resourceManager.findResources("weapon_attributes", fileName -> fileName.endsWith(".json"))) {
            try {
                // System.out.println("Checking resource: " + identifier);
                var resource = resourceManager.getResource(identifier);
                JsonReader reader = new JsonReader(new InputStreamReader(resource.getInputStream()));
                AttributesContainer container = gson.fromJson(reader, fileFormat);
                var id = identifier
                        .toString().replace("weapon_attributes/", "");
                id = id.substring(0, id.lastIndexOf('.'));
                containers.put(new Identifier(id), container);
            } catch (Exception e) {
                System.err.println("Failed to parse: " + identifier);
                e.printStackTrace();
            }
        }

        // Resolving parents
        containers.forEach( (key, value) -> {
            if (!Registry.ITEM.containsId(key)) {
                return;
            }
            try {
                ArrayList<WeaponAttributes> resolutionChain = new ArrayList();
                AttributesContainer current = value;
                while (current != null) {
                    resolutionChain.add(0, current.attributes());
                    if (current.parent() != null) {
                        current = containers.get(new Identifier(current.parent()));
                    } else {
                        current = null;
                    }
                }

                var empty = new WeaponAttributes(0, null, false, null);
                var resolvedAttributes = resolutionChain
                    .stream()
                    .reduce(empty, (a, b) -> {
                        if (b == null) { // I'm not sure why null can enter as `b`
                            return a;
                        }
                        return override(a, b);
                    });

                register(key, resolvedAttributes);
            } catch (Exception e) {
                System.err.println("Failed to resolve inheritance path of: " + key);
                e.printStackTrace();
            }
        });

        updateEncodedRegistry();
    }

    private static WeaponAttributes override(WeaponAttributes a, WeaponAttributes b) {
        var attackRange = b.attackRange() > 0 ? b.attackRange() : a.attackRange();
        var pose = b.pose() != null ? b.pose() : a.pose();
        var isTwoHanded = b.isTwoHanded();
        var attacks = a.attacks();
        if (b.attacks() != null && b.attacks().length > 0) {
            var overrideAttacks = new ArrayList<WeaponAttributes.Attack>();
            for(int i = 0; i < b.attacks().length; ++i) {
                var base = (a.attacks() != null && a.attacks().length > i)
                        ? a.attacks()[i]
                        : new WeaponAttributes.Attack(null, 0, 0, 0, null, null, null);
                var override = b.attacks()[i];
                var attack = new WeaponAttributes.Attack(
                        override.hitbox() != null ? override.hitbox() : base.hitbox(),
                        override.damageMultiplier() != 0 ? override.damageMultiplier() : base.damageMultiplier(),
                        override.angle() != 0 ? override.angle() : base.angle(),
                        override.upswing() != 0 ? override.upswing() : base.upswing(),
                        override.animation() != null ? override.animation() : base.animation(),
                        override.swingSound() != null ? override.swingSound() : base.swingSound(),
                        override.impactSound() != null ? override.impactSound() : base.impactSound());
                overrideAttacks.add(attack);
            }
            attacks = overrideAttacks.toArray(new WeaponAttributes.Attack[0]);
        }
        return new WeaponAttributes(attackRange, pose, isTwoHanded, attacks);
    }

    // NETWORK SYNC

    private static PacketByteBuf encodedRegistrations = PacketByteBufs.create();

    public static void updateEncodedRegistry() {
        PacketByteBuf buffer = PacketByteBufs.create();
        var gson = new Gson();
        var json = gson.toJson(registrations);
        System.out.println("Updated Weapon Attribute registry: " + json);
        buffer.writeString(json);
        System.out.println("Encoded Weapon Attribute registry size (with package overhead): " + buffer.readableBytes() + " bytes");
        encodedRegistrations = buffer;
    }

    public static void loadEncodedRegistry(PacketByteBuf buffer) {
        String json = buffer.readString();
        var gson = new Gson();
        Type mapType = new TypeToken<Map<String, WeaponAttributes>>() {}.getType();
        Map<String, WeaponAttributes> readRegistrations = gson.fromJson(json, mapType);
        Map<Identifier, WeaponAttributes> newRegistrations = new HashMap();
        readRegistrations.forEach((key, value) -> {
            newRegistrations.put(new Identifier(key), value);
        });
        registrations = newRegistrations;
    }

    public static PacketByteBuf getEncodedRegistry() {
        return encodedRegistrations;
    }
}