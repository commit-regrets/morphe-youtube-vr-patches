import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The dex reader behind scripts/audit-patched-apk.ps1. Two questions, both answered by "which
 * instruction in which method references that string":
 *
 * <pre>
 *   inventory &lt;dex...&gt;        every *app-side* method that references this port's extension
 *   callers &lt;needle&gt; &lt;dex...&gt;  every method that references anything containing &lt;needle&gt;
 * </pre>
 *
 * Output is one `owner#method|opcode|reference` line per hit, app-side only for `inventory` (the
 * extension's own internal calls are noise: they are not injections). `callers` has no such filter —
 * it is how a hooked method's own callers are counted, including the extension's.
 *
 * The classpath comes from the Gradle cache (see the script); this deliberately uses the patcher's
 * own dexlib2 so the reader and the patcher agree on what the dex says.
 */
public class DexAudit {

    private static final String EXTENSION_PACKAGE = "Lapp/ytvr/extension/";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DexAudit <inventory|callers> <needle|-> <dex...>");
            System.exit(2);
        }
        String mode = args[0];
        String needle = args[1];
        boolean inventory = "inventory".equals(mode);
        if (!inventory && !"callers".equals(mode)) {
            System.err.println("unknown mode: " + mode);
            System.exit(2);
        }
        for (int i = 2; i < args.length; i++) {
            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(), new BufferedInputStream(new FileInputStream(args[i])));
            for (ClassDef c : dex.getClasses()) {
                boolean appSide = !c.getType().startsWith(EXTENSION_PACKAGE);
                if (inventory && !appSide) {
                    continue;
                }
                for (Method m : c.getMethods()) {
                    if (m.getImplementation() == null) {
                        continue;
                    }
                    List<Instruction> instructions = new ArrayList<>();
                    for (Instruction ins : m.getImplementation().getInstructions()) {
                        instructions.add(ins);
                    }
                    for (Instruction ins : instructions) {
                        if (!(ins instanceof ReferenceInstruction)) {
                            continue;
                        }
                        String ref = String.valueOf(((ReferenceInstruction) ins).getReference());
                        if (inventory ? ref.contains(EXTENSION_PACKAGE) : ref.contains(needle)) {
                            System.out.println(c.getType() + "#" + m.getName() + "|"
                                + ins.getOpcode() + "|" + ref);
                        }
                    }
                }
            }
        }
    }
}
