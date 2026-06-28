import com.google.gson.Gson // 데이터를 JSON 형식으로 변환하기 위한 라이브러리를 불러옵니다.
import com.google.gson.GsonBuilder // JSON 변환 시 세부 설정을 하기 위한 빌더 클래스를 불러옵니다.
import java.io.File // 파일 시스템에 접근하기 위한 클래스를 불러옵니다.

// 사용자의 설정 및 대화 데이터를 파일로 저장하고 불러오는 기능을 담당하는 객체입니다.
object PersistenceManager {
    // 설정 데이터가 저장될 파일의 이름을 정의합니다.
    private const val FILE_NAME = "config.json"
    // JSON 데이터를 예쁘게 출력하고 직렬화하기 위한 Gson 인스턴스를 생성합니다.
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // 설정 데이터를 파일에서 읽어와 AppConfig 객체로 변환합니다.
    fun load(): AppConfig {
        // 파일 객체를 생성합니다.
        val file = File(FILE_NAME)
        // 파일이 존재하지 않는 경우 새로운 기본 설정 객체를 생성하여 반환합니다.
        if (!file.exists()) return AppConfig()
        // 파일 내용을 텍스트로 읽어와 JSON을 AppConfig 객체로 파싱하여 반환합니다.
        return try {
            gson.fromJson(file.readText(), AppConfig::class.java)
        } catch (e: Exception) {
            // 파싱 중 오류가 발생하면 빈 기본 설정 객체를 반환합니다.
            AppConfig()
        }
    }

    // AppConfig 객체를 JSON 형식으로 변환하여 파일에 저장합니다.
    fun save(config: AppConfig) {
        // AppConfig 객체를 JSON 텍스트로 변환합니다.
        val json = gson.toJson(config)
        // 변환된 텍스트를 지정된 파일에 기록합니다.
        File(FILE_NAME).writeText(json)
    }
}
