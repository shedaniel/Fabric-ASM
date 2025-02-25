/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package me.shedaniel.mm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.collect.Maps;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.extensions.ExtensionClassExporter;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import me.shedaniel.mm.EnumSubclasser.StructClass;
import me.shedaniel.mm.api.ClassTinkerers;
import me.shedaniel.mm.api.EnumAdder;
import me.shedaniel.mm.api.EnumAdder.EnumAddition;

public final class Plugin implements IMixinConfigPlugin {
	final List<String> mixins = new ArrayList<>();
	final Map<String, String> enumStructParents = new HashMap<>();
	private Map<String, Set<ClassNodeConsumer>> classModifiers;

	private static Consumer<URL> fishAddURL() {
		ClassLoader loader = Plugin.class.getClassLoader();
		Method addUrlMethod = null;
		for (Method method : loader.getClass().getDeclaredMethods()) {
			/*System.out.println("Type: " + method.getReturnType());
			System.out.println("Params: " + method.getParameterCount() + ", " + Arrays.toString(method.getParameterTypes()));*/
			if (method.getReturnType() == Void.TYPE && method.getParameterCount() == 1 && method.getParameterTypes()[0] == URL.class) {
				addUrlMethod = method; //Probably
				break;
			}
		}
		if (addUrlMethod == null) throw new IllegalStateException("Couldn't find method in " + loader);
		try {
			addUrlMethod.setAccessible(true);
			MethodHandle handle = MethodHandles.lookup().unreflect(addUrlMethod);
			return url -> {
				try {
					handle.invoke(loader, url);
				} catch (Throwable t) {
					throw new RuntimeException("Unexpected error adding URL", t);
				}
			};
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Couldn't get handle for " + addUrlMethod, e);
		}
	}

	@Override
	public void onLoad(String rawMixinPackage) {
		String mixinPackage = rawMixinPackage.replace('.', '/');
		MM.LOGGER.info("Started MM (shedaniel's fork) at " + mixinPackage);

		//transforms.computeIfAbsent("net.minecraft.item.ItemStack", k -> new HashSet<>()).add("<*>");
		//this.transforms.add("net.minecraft.class_1234");
		/*transforms.computeIfAbsent("net.minecraft.client.MinecraftClient", k -> new HashSet<>()).add("<*>");
		transforms.computeIfAbsent("net.minecraft.entity.passive.SheepEntity", k -> new HashSet<>()).add("<*>");
		transforms.computeIfAbsent("net.minecraft.client.gui.ingame.CreativePlayerInventoryScreen$CreativeSlot", k -> new HashSet<>()).add("<*>");*/

		Map<String, byte[]> classGenerators = new HashMap<>();
		Map<String, Set<ClassNodeConsumer>> classModifiers = new HashMap<String, Set<ClassNodeConsumer>>() {
			private static final long serialVersionUID = 4152702952481261028L;
			private boolean skipGen = false;
			private int massPool = 1;

			private void generate(String name, Collection<? extends String> targets) {
				//System.out.println("Generating " + mixinPackage + name + " with targets " + targets);
				assert name.indexOf('.') < 0;
				classGenerators.put('/' + mixinPackage + name + ".class", makeMixinBlob(mixinPackage + name, targets));
				//ClassTinkerers.define(mixinPackage + name, makeMixinBlob(mixinPackage + name, targets)); ^^^
				mixins.add(name.replace('/', '.'));
			}

			@Override
			public Set<ClassNodeConsumer> put(String key, Set<ClassNodeConsumer> value) {
				if (!skipGen) generate(key, Collections.singleton(key));
				return super.put(key, value);
			}

			@Override
			public void putAll(Map<? extends String, ? extends Set<ClassNodeConsumer>> m) {
				skipGen = true;
				generate("MassExport_" + massPool++, m.keySet());
				super.putAll(m);
				skipGen = false;
			}
		};
		Map<String, Consumer<ClassNode>> classReplacers = new HashMap<String, Consumer<ClassNode>>() {
			private static final long serialVersionUID = -1226882557454215762L;
			private boolean skipGen = false;

			@Override
			public Consumer<ClassNode> put(String key, Consumer<ClassNode> value) {
				if (!skipGen && !classModifiers.containsKey(key)) classModifiers.put(key, new HashSet<>());
				return super.put(key, value);
			}

			@Override
			public void putAll(Map<? extends String, ? extends Consumer<ClassNode>> m) {
				skipGen = true;
				//Avoid squishing anything if it's already there, otherwise make an empty set
				classModifiers.putAll(Maps.asMap(m.keySet(), name -> classModifiers.getOrDefault(name, new HashSet<>())));
				super.putAll(m);
				skipGen = false;
			}
		};
		Set<EnumAdder> enumExtenders = new HashSet<EnumAdder>() {
			private static final long serialVersionUID = -2218861530200981246L;
			private boolean skipCheck = false;

			private void addTransformations(EnumAdder builder) {
				ClassTinkerers.addTransformation(builder.type, EnumExtender.makeEnumExtender(builder));

				for (EnumAddition addition : builder.getAdditions()) {
					if (addition.isEnumSubclass()) {
						ClassTinkerers.addReplacement(addition.structClass, EnumSubclasser.makeStructFixer(addition, builder.type));

						for (StructClass node : EnumSubclasser.getParentStructs(addition.structClass)) {
							String lastEnum = enumStructParents.put(node.name, builder.type);
							assert lastEnum == null || lastEnum.equals(builder.type);
						}
					}
				}

				enumStructParents.keySet().removeAll(classReplacers.keySet());
			}

			@Override
			public boolean add(EnumAdder builder) {
				if (!skipCheck) addTransformations(builder);
				return super.add(builder);
			}

			@Override
			public boolean addAll(Collection<? extends EnumAdder> builders) {
				skipCheck = true;
				for (EnumAdder builder : builders) addTransformations(builder);
				boolean out = super.addAll(builders);
				skipCheck = false;
				return out;
			}

			@Override
			public boolean remove(Object o) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean removeIf(Predicate<? super EnumAdder> filter) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean removeAll(Collection<?> c) {
				throw new UnsupportedOperationException();
			}
		};
		ClassTinkerers.INSTANCE.hookUp(fishAddURL(), new UnremovableMap<>(classGenerators), new UnremovableMap<>(classReplacers), new UnremovableMap<>(classModifiers), enumExtenders);

		ClassTinkerers.addURL(CasualStreamHandler.create(classGenerators));
		this.classModifiers = classModifiers;

		//System.out.println("Loaded initially with: " + classModifiers);

		Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
		if (transformer == null) throw new IllegalStateException("Not running with a transformer?");

		Extensions extensions = null;
		try {
			for (Field f : transformer.getClass().getDeclaredFields()) {
				if (f.getType() == Extensions.class) {
					f.setAccessible(true); //Knock knock, we need this
					extensions = (Extensions) f.get(transformer);
					break;
				}
			}

			if (extensions == null) {
				String foundFields = Arrays.stream(transformer.getClass().getDeclaredFields()).map(f -> f.getType() + " " + f.getName()).collect(Collectors.joining(", "));
				throw new NoSuchFieldError("Unable to find extensions field, only found " + foundFields);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Running with a transformer that doesn't have extensions?", e);
		}

		extensions.add(new Extension(mixinPackage, classReplacers));
		ExtensionClassExporter exporter = extensions.getExtension(ExtensionClassExporter.class);
		CasualStreamHandler.dumper = (name, bytes) -> {
			ClassNode node = new ClassNode(); //Read the bytes in as per TreeTransformer#readClass(byte[])
			new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
			exporter.export(MixinEnvironment.getCurrentEnvironment(), name, false, node);
		};
	}

	static byte[] makeMixinBlob(String name, Collection<? extends String> targets) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, name, null, "java/lang/Object", null);

		AnnotationVisitor mixinAnnotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targetAnnotation = mixinAnnotation.visitArray("value");
		for (String target : targets) targetAnnotation.visit(null, Type.getType('L' + target + ';'));
		targetAnnotation.visitEnd();
		mixinAnnotation.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static final int ACCESSES = ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
	private static int flipBits(int access) {
		access &= ACCESSES;
		access |= Opcodes.ACC_PUBLIC;
		access &= ~Opcodes.ACC_FINAL;
		return access;
	}

	@Override
	public String getRefMapperConfig() {
		return null; //We can rely on the default
	}

	@Override
	public List<String> getMixins() {
		//System.out.println("Have " + mixins);
		FabricLoader.getInstance().getEntrypoints("mm_shedaniel:early_risers", Runnable.class).forEach(Runnable::run);
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (mod.getMetadata().containsCustomValue("mm_shedaniel:early_risers")) {
				System.out.println(mod.getMetadata().getName() + " is still using the traditional Early Riser initialisation");
				for (CustomValue riser : mod.getMetadata().getCustomValue("mm_shedaniel:early_risers").getAsArray()) {
					try {
						Class.forName(riser.getAsString()).asSubclass(Runnable.class).newInstance().run();
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException("Error loading (shedaniel's fork) early riser from " + mod.getMetadata().getId(), e);
					}
				}
			}
		}
		//System.out.println("Now have " + mixins);
		if (!enumStructParents.isEmpty()) {
			for (Entry<String, String> entry : enumStructParents.entrySet()) {
				ClassTinkerers.addReplacement(entry.getKey(), EnumSubclasser.makeStructFixer(entry.getKey(), entry.getValue()));
			}
		}
		return mixins;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true; //Sure
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		//System.out.println("Pre-applying " + targetClassName + " via " + mixinClassName);

		Set<ClassNodeConsumer> transformations = classModifiers.get(targetClassName.replace('.', '/'));
		if (transformations != null) {
			for (ClassNodeConsumer transformer : transformations) {
				transformer.accept(targetClass);
			}
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		targetClass.interfaces.remove(mixinClassName.replace('.', '/'));

		Set<ClassNodeConsumer> transformations = classModifiers.get(targetClassName.replace('.', '/'));
		if (transformations != null) {
			for (ClassNodeConsumer transformer : transformations) {
				transformer.acceptPost(targetClass);
			}
		}
	}
}