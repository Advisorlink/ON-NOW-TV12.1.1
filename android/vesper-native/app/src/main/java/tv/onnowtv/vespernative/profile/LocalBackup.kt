package tv.onnowtv.vespernative.profile

import java.io.InputStream
import java.io.ByteArrayOutputStream

object LocalBackup {
    fun read(input:InputStream):String {
        val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=65536){"Settings file is too large"};output.write(buffer,0,count)}
        return output.toString(Charsets.UTF_8.name())
    }
}