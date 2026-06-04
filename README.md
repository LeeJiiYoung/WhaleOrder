그라파나 해보기


http://localhost:3001 접속 (admin / admin1234)
Connections → Data Sources → Add data source → Prometheus
URL: http://prometheus:9090 입력 후 Save
Dashboards → New → Add visualization
메트릭 선택:
order_processed_total — 주문 처리 횟수
payment_processed_total — 결제 횟수
order_stock_shortage_total — 재고 부족 횟수
order_processing_time_seconds — 처리 시간
