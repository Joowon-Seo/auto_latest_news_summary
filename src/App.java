import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class App {

	public static void main(String[] args) {
		String clientId = System.getenv("CLIENT_ID");
		String clientSecret = System.getenv("CLIENT_SECRET");
		String keyword = "AI";
		String text = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
		String apiURL = "https://openapi.naver.com/v1/search/news?query=" + text
				+ "&sort=date&display=1";

		Map<String, String> requestHeaders = new HashMap<>();
		requestHeaders.put("X-Naver-Client-Id", clientId);
		requestHeaders.put("X-Naver-Client-Secret", clientSecret);

		String newsResponse = get(apiURL, requestHeaders);
		String latestNews = extractLatestNews(newsResponse);

		String geminiApiKey = System.getenv("GEMINI_KEY");
		String summary = summarizeWithGemini(latestNews, geminiApiKey);

		saveToMarkdown(keyword, latestNews, summary);
	}

	private static String get(String apiUrl, Map<String, String> requestHeaders) {
		HttpURLConnection con = connect(apiUrl);
		try {
			con.setRequestMethod("GET");
			requestHeaders.forEach(con::setRequestProperty);
			int responseCode = con.getResponseCode();
			InputStream stream =
					(responseCode == HttpURLConnection.HTTP_OK) ? con.getInputStream() :
							con.getErrorStream();
			return readBody(stream);
		} catch (IOException e) {
			throw new RuntimeException("API 요청과 응답 실패", e);
		} finally {
			con.disconnect();
		}
	}

	private static HttpURLConnection connect(String apiUrl) {
		try {
			return (HttpURLConnection)new URL(apiUrl).openConnection();
		} catch (IOException e) {
			throw new RuntimeException("API URL 연결 실패: " + apiUrl, e);
		}
	}

	private static String readBody(InputStream body) {
		try (BufferedReader lineReader = new BufferedReader(
				new InputStreamReader(body))) {
			StringBuilder responseBody = new StringBuilder();
			String line;
			while ((line = lineReader.readLine()) != null) {
				responseBody.append(line);
			}
			return responseBody.toString();
		} catch (IOException e) {
			throw new RuntimeException("API 응답을 읽는 데 실패했습니다.", e);
		}
	}

	private static String extractLatestNews(String jsonResponse) {
		try {
			int itemsStart = jsonResponse.indexOf("\"items\":") + 8;
			int firstItemStart = jsonResponse.indexOf("{", itemsStart);
			int firstItemEnd = jsonResponse.indexOf("}", firstItemStart) + 1;

			if (firstItemStart == -1 || firstItemEnd == -1) {
				return "뉴스 데이터 없음";
			}

			String item = jsonResponse.substring(firstItemStart, firstItemEnd);
			String title = extractField(item, "\"title\":\"", "\"");
			String link = extractField(item, "\"link\":\"", "\"");
			String description = extractField(item, "\"description\":\"", "\"");
			String pubDate = extractField(item, "\"pubDate\":\"", "\"");

			return String.format(
					"""
									제목: %s
									링크: %s
									설명: %s
									게시일: %s
							""",
					title != null ? title : "N/A",
					link != null ? link : "N/A",
					description != null ? description : "N/A",
					pubDate != null ? pubDate : "N/A");
		} catch (Exception e) {
			return "뉴스 파싱 실패: " + e.getMessage();
		}
	}

	private static String extractField(String source, String startMarker,
			String endMarker) {
		int start = source.indexOf(startMarker);
		if (start == -1) return null;
		start += startMarker.length();
		int end = source.indexOf(endMarker, start);
		if (end == -1) return null;
		return source.substring(start, end).replace("\\\"", "\"").replace("\\n", " ");
	}

	private static String summarizeWithGemini(String newsContent, String apiKey) {
		String apiUrl =
				"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
						+ apiKey;
		String prompt = "다음 뉴스를 100자 이내로 요약해줘:\n" + newsContent;
		String jsonInput = """
				{
				  "contents": [{
				    "parts": [{"text": "%s"}]
				  }]
				}
				""".formatted(prompt);

		try {
			HttpURLConnection con = connect(apiUrl);
			con.setRequestMethod("POST");
			con.setRequestProperty("Content-Type", "application/json");
			con.setDoOutput(true);

			try (OutputStream os = con.getOutputStream()) {
				byte[] input = jsonInput.getBytes(StandardCharsets.UTF_8);
				os.write(input, 0, input.length);
			}

			int responseCode = con.getResponseCode();
			String response = (responseCode == HttpURLConnection.HTTP_OK) ?
					readBody(con.getInputStream()) : readBody(con.getErrorStream());
			con.disconnect();

			// 디버깅용 출력
			System.out.println("Gemini response = " + response);

			// "text" 값 직접 추출
			return extractTextFromGeminiResponse(response);
		} catch (IOException e) {
			throw new RuntimeException("Gemini API 호출 실패", e);
		}
	}

	private static String extractTextFromGeminiResponse(String response) {
		String marker = "text";
		int startIndex = response.indexOf(marker) + 4;
		if (startIndex == 3) {
			return "text 필드 없음";
		}
		startIndex += marker.length();
		int endIndex = response.indexOf("\\n", startIndex);
		if (endIndex == -1) {
			return "text 종료 지점 없음";
		}
		return response.substring(startIndex, endIndex).replace("\\n", " ");
	}

	private static void saveToMarkdown(String keyword, String newsContent,
			String summary) {
		// 현재 시간을 "YYYY년 MM월 DD일 HH시 MM분" 형식으로 변환
		LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 HH시 mm분");
		String dateStr = now.format(formatter);

		// "news" 디렉토리에 저장
		String directory = "news";
		File dir = new File(directory);
		if (!dir.exists()) {
			dir.mkdirs(); // 디렉토리가 없으면 생성
		}

		String fileName = directory + "/latest_ai_news_" + dateStr + ".md";
		String markdownContent = """
                # 최신 AI 뉴스 요약
                
                ## 원본 뉴스
                %s
                
                ## 요약
                %s
                """.formatted(newsContent, summary);

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
			writer.write(markdownContent);
			System.out.println("파일 생성 완료: " + fileName);
		} catch (IOException e) {
			throw new RuntimeException("Markdown 파일 저장 실패", e);
		}
	}
}