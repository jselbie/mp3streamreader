/*
   Copyright 2014 John Selbie

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.selbie.mp3;

import java.io.IOException;
import java.io.InputStream;

import android.util.Log;

public class MP3StreamReader
{
    public static class FormatDetails
    {
        public int samplerate;
        public int channelcount;
        public int bitrate;
        public int maxframesize; // max frame size including potential padding
    }
    
    
    public final String TAG = MP3StreamReader.class.getSimpleName();
    
    byte [] _frameheader;
    InputStream _stream;
    
    
    static final int [][][] mpeg_bitrates = {
    { // Version 2.5
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }, // Reserved
      { 0,   8,  16,  24,  32,  40,  48,  56,  64,  80,  96, 112, 128, 144, 160, 0 }, // Layer 3
      { 0,   8,  16,  24,  32,  40,  48,  56,  64,  80,  96, 112, 128, 144, 160, 0 }, // Layer 2
      { 0,  32,  48,  56,  64,  80,  96, 112, 128, 144, 160, 176, 192, 224, 256, 0 }  // Layer 1
    },
    { // Reserved
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }, // Invalid
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }, // Invalid
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }, // Invalid
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }  // Invalid
    },
    { // Version 2
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }, // Reserved
      { 0,   8,  16,  24,  32,  40,  48,  56,  64,  80,  96, 112, 128, 144, 160, 0 }, // Layer 3
      { 0,   8,  16,  24,  32,  40,  48,  56,  64,  80,  96, 112, 128, 144, 160, 0 }, // Layer 2
      { 0,  32,  48,  56,  64,  80,  96, 112, 128, 144, 160, 176, 192, 224, 256, 0 }  // Layer 1
    },
    { // Version 1
      { 0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0, 0 }, // Reserved
      { 0,  32,  40,  48,  56,  64,  80,  96, 112, 128, 160, 192, 224, 256, 320, 0 }, // Layer 3
      { 0,  32,  48,  56,  64,  80,  96, 112, 128, 160, 192, 224, 256, 320, 384, 0 }, // Layer 2
      { 0,  32,  64,  96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0 }, // Layer 1
    }};    
    
    // Sample rates - use [version][srate]
    static final int [][] mpeg_srates = {
        { 11025, 12000,  8000, 0 }, // MPEG 2.5
        {     0,     0,     0, 0 }, // Reserved
        { 22050, 24000, 16000, 0 }, // MPEG 2
        { 44100, 48000, 32000, 0 }  // MPEG 1
    };
    
    // Samples per frame - use [version][layer]
    static final int [][] mpeg_frame_samples = {
//        Rsvd     3     2     1  < Layer  v Version
        {    0,  576, 1152,  384 }, //       2.5
        {    0,    0,    0,    0 }, //       Reserved
        {    0,  576, 1152,  384 }, //       2
        {    0, 1152, 1152,  384 }  //       1
    };
    
    static final int [] mpeg_slot_size = { 0, 1, 1, 4 }; // Rsvd, 3, 2, 1
    
    static final int [] mpeg_channels = {2, 2, 2, 1};
    
    
    

    public MP3StreamReader(InputStream stream)
    {
        _frameheader = new byte[4];
        _stream = stream;
    }
    
    
    // I'm not sure if a wrapper is needed for InputStream.read().  I'm considering the case of a socket stream
    // in which data my arrive in chunks. As such, the read() call may return partial data.  TBD if this is needed
    private int synchronousRead(byte [] block, int offset, int count) throws IOException
    {
        int bytes_read = 0;
        int bytes_remaining = count;
        int result = 0;
        
        while (bytes_remaining > 0)
        {
            result = _stream.read(block, bytes_read, bytes_remaining);
            if (result < 0)
            {
                return -1;
            }
            bytes_read += result;
            bytes_remaining -= result;
        }
        
        return count;
    }
    
    private int getFrameSize(FormatDetails details)
    {
        if (((_frameheader[0] & 0xff) != 0xff) ||
            ((_frameheader[1] & 0xe0) != 0xe0) ||
            ((_frameheader[1] & 0x18) == 0x08) ||
            ((_frameheader[1] & 0x06) == 0x00) ||
            ((_frameheader[2] & 0xf0) == 0xf0))
        {
            return 0;
        }
        
        byte ver = (byte)((_frameheader[1] & 0x18) >> 3);  // version index
        byte lyr = (byte)((_frameheader[1] & 0x06) >> 1);  // layer index
        byte pad = (byte)((_frameheader[2] & 0x02) >> 1);  // padding bit
        byte brx = (byte)((_frameheader[2] & 0xf0) >> 4);  // bitrate index
        byte srx = (byte)((_frameheader[2] & 0x0c) >> 2);  // samplerate index
        byte chn = (byte)((_frameheader[3] & 0xc0) >> 6);  // number of channels
        
        int bitrate = mpeg_bitrates[ver][lyr][brx] * 1000;
        int samprate  = mpeg_srates[ver][srx];
        int samples   = mpeg_frame_samples[ver][lyr];
        int slot_size  = mpeg_slot_size[lyr];
        int channels = mpeg_channels[chn];
        int padding = (pad == 0) ? 0 : slot_size;
        
        
        int frame_size = ((samples * bitrate) / (8*samprate)) + padding;
        
        if (details != null)
        {
            details.channelcount = channels;
            details.bitrate = bitrate;
            details.samplerate = samprate;
            details.maxframesize = frame_size + slot_size;  
        }
        
        return frame_size;
    }
    
    
    public int read_next_chunk(byte [] framebuffer, FormatDetails details) throws IOException
    {
        int readresult = 0;
        int framesize = 0;
        int skipcount = 0;
        
        _frameheader[0] = 0;
        _frameheader[1] = 0;
        _frameheader[2] = 0;
        _frameheader[3] = 0;
        
        readresult = synchronousRead(_frameheader, 0, _frameheader.length);
        
        if (readresult < 0)
        {
            return -1;
        }
        
        while ((framesize=getFrameSize(details)) == 0)
        {
            skipcount++;
            
            // shift everything over by 1 and read another sample
            _frameheader[0] = _frameheader[1];
            _frameheader[1] = _frameheader[2];
            _frameheader[2] = _frameheader[3];
            _frameheader[3] = 0;
            
            readresult = _stream.read();
            if (readresult == -1)
            {
                return -1; // EOF
            }
            _frameheader[3] = (byte)readresult;
            
        }
        
        if (skipcount > 0)
        {
            Log.d(TAG, "skipping over " + skipcount + " bytes");
        }
        
        // copy over the frameheader
        System.arraycopy(_frameheader, 0, framebuffer, 0, 4);
        
        // now read the rest of the chunk
        readresult = synchronousRead(framebuffer, 4, framesize-4);
        
        if (readresult < 0)
        {
            return -1; // EOF
        }
        
        return framesize;
    }
    
    
}
