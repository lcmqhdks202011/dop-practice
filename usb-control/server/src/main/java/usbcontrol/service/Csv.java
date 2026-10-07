package usbcontrol.service;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 엑셀에서 한글이 깨지지 않는 CSV 파일 내려받기 */
public final class Csv {

    private Csv() {
    }

    public static void write(HttpServletResponse response, String fileName,
                             List<String> header, List<List<Object>> rows) throws IOException {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.write('﻿');
        out.write(line(header.stream().map(h -> (Object) h).toList()));
        for (List<Object> row : rows) {
            out.write(line(row));
        }
        out.flush();
    }

    private static String line(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            Object cell = cells.get(i);
            String text = cell == null ? "" : cell.toString();
            // 엑셀이 = + - @ 로 시작하는 칸을 수식으로 실행하지 않도록 막습니다.
            if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
                text = "'" + text;
            }
            sb.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        return sb.append("\r\n").toString();
    }
}
