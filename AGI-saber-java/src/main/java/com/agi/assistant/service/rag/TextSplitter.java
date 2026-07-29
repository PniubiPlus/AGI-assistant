package com.agi.assistant.service.rag;

import com.agi.assistant.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Unicode-safe text splitter with sliding window overlap.
 */
@Component
public class TextSplitter {

    private int chunkSize = 200;
    private int overlap = 50;

    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public void setOverlap(int overlap) { this.overlap = overlap; }

    public List<Chunk> split(String text) {
        List<Chunk> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        if (step <= 0) step = chunkSize;

        int[] codePoints = text.codePoints().toArray();
        int id = 0;
        for (int i = 0; i < codePoints.length; i += step) {
            int end = Math.min(i + chunkSize, codePoints.length);
            String content = new String(codePoints, i, end - i);
            chunks.add(new Chunk(id++, content));
            if (end >= codePoints.length) break;
        }
        return chunks;
    }
}
