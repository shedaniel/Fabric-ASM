/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package me.shedaniel.mm;

import org.objectweb.asm.tree.ClassNode;

import java.util.function.Consumer;

public interface ClassNodeConsumer {
    static ClassNodeConsumer ofPre(Consumer<ClassNode> consumer) {
        return new ClassNodeConsumer() {
            @Override
            public void accept(ClassNode node) {
                consumer.accept(node);
            }
        };
    }
    
    static ClassNodeConsumer ofPost(Consumer<ClassNode> consumer) {
        return new ClassNodeConsumer() {
            @Override
            public void acceptPost(ClassNode node) {
                consumer.accept(node);
            }
        };
    }
    
    static ClassNodeConsumer of(Consumer<ClassNode> consumer, boolean post) {
        return post ? ofPost(consumer) : ofPre(consumer);
    }
    
    default void accept(ClassNode node) {
    }
    
    default void acceptPost(ClassNode node) {
    }
}
