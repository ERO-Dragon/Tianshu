package com.rheinmetal.tianshu.client.junk;

import com.rheinmetal.tianshu.function.junk.JunkClearRequestGate;
import com.rheinmetal.tianshu.network.C2SRequestClearJunkPacket;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Set;

public final class JunkClearRequestClientGateway {
    private JunkClearRequestClientGateway() {
    }

    public static Result request(Set<String> itemIds) {
        List<String> requested = itemIds == null ? List.of() : itemIds.stream().toList();
        JunkClearRequestGate.Decision decision = JunkClearRequestGate.evaluate(requested);
        if (!decision.allowed()) {
            return Result.denied(decision.message());
        }
        PacketDistributor.sendToServer(new C2SRequestClearJunkPacket(decision.itemIds()));
        return Result.sent(decision.itemIds().size());
    }

    public record Result(boolean sent, int itemTypeCount, String message) {
        public static Result sent(int itemTypeCount) {
            return new Result(true, itemTypeCount, "已请求清理垃圾");
        }

        public static Result denied(String message) {
            return new Result(false, 0, message == null ? "" : message);
        }
    }
}
