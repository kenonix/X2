package org.example.x2

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

// 사용자의 설정 및 대화 데이터를 모바일 기기의 파일 시스템으로 저장하고 불러오는 기능을 담당하는 객체입니다.
object PersistenceManager {
    // 설정 데이터가 저장될 파일의 이름을 정의합니다.
    private const val FILE_NAME = "config.json"
    
    // JSON 데이터를 예쁘게 출력하고 직렬화하기 위한 Gson 인스턴스를 생성합니다.
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // 설정 데이터를 기기 내부 파일에서 읽어와 AppConfig 객체로 변환합니다.
    fun load(context: Context): AppConfig {
        // 앱 전용 내부 저장소 경로에 파일 객체를 생성합니다.
        val file = File(context.filesDir, FILE_NAME)
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

    // AppConfig 객체를 JSON 형식으로 변환하여 기기 내부 파일에 저장합니다.
    fun save(context: Context, config: AppConfig) {
        // 앱 전용 내부 저장소 경로에 파일 객체를 생성합니다.
        val file = File(context.filesDir, FILE_NAME)
        try {
            // AppConfig 객체를 JSON 텍스트로 변환합니다.
            val json = gson.toJson(config)
            // 변환된 텍스트를 파일에 기록합니다.
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
