import ccsuite.ccacc.CcaccIcon;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public class MakeIco {

    static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws Exception {
        var pngs = new byte[SIZES.length][];
        for (var i = 0; i < SIZES.length; i++) {
            var out = new ByteArrayOutputStream();
            ImageIO.write(CcaccIcon.draw(SIZES[i]), "png", out);
            pngs[i] = out.toByteArray();
        }
        var offset = 6 + 16 * SIZES.length;
        var head = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        for (var i = 0; i < SIZES.length; i++) {
            var wh = (byte) (SIZES[i] == 256 ? 0 : SIZES[i]);
            head.put(wh).put(wh).put((byte) 0).put((byte) 0);
            head.putShort((short) 1).putShort((short) 32);
            head.putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        try (var file = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
            file.write(head.array());
            for (var png : pngs) file.write(png);
        }
        System.err.println("wrote " + args[0] + " (" + offset + " bytes)");
    }
}
