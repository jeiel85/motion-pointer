# Backlog

- [ ] 갤럭시 버즈(360 오디오 지원 모델) 센서 지원: PC 앱이 버즈의 SPP(RFCOMM)에 붙어 자세 쿼터니언을 받는다. 참고: GalaxyBudsClient `SpatialSensorManager`. 실물 버즈가 있어야 검증할 수 있다. 버즈 FE는 센서 데이터가 없어 불가.
- [ ] 화면이 꺼져도 동작: 포그라운드 서비스로 센서와 연결을 유지한다.
- [ ] 패드 탭으로 클릭: 짧게 톡 치면 왼쪽 클릭. 누르기 시작할 때 커서가 흔들리지 않게 처리가 필요하다.
- [ ] HID 절대 좌표(디지타이저) 보고서: 운영체제의 마우스 가속 없이 PC 앱 모드와 같은 감각을 내도록.
- [ ] 실사용 후 감도 기본값(12/40 px/°, 20/200 °/s) 재조정.
- [ ] 연결 해제·오류 안내가 `onResume`의 `loadDevices()`에서 덮어씌워질 수 있음. 짧은 메시지가 유지되도록 우선순위 또는 표시 시간 처리.
- [ ] `loadDevices()`가 호출될 때마다 새 `ArrayAdapter`를 만들어 Spinner에 할당. 목록이 같으면 갱신을 생략하거나 어댑터를 재사용.
- [ ] RTL에서 `setUpEdgeToEdge()`의 논리적 paddingStart/End와 물리적 `setPadding()` 조합으로 좌우 인셋이 뒤바뀔 수 있음. `layoutDirection` configChanges 처리와 함께 검토.
- [ ] 사용되지 않는 `res/color/body_text.xml` 정리.
