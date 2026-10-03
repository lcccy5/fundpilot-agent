"""公司行业信息来自公开接口；产业链位置只采用透明规则做初步归类。"""


def chain_role(text: str) -> str:
    groups = [
        ("上游材料与零部件", ("减速器", "传感器", "电机", "轴承", "原材料", "化肥", "农药")),
        ("中游生产与制造", ("机器人本体", "系统集成", "自动化设备", "养殖", "种植", "制造")),
        ("下游应用与消费", ("汽车", "仓储", "物流", "医疗", "食品加工", "应用", "服务")),
    ]
    for role, words in groups:
        if any(word in text for word in words):
            return role
    return "未能确定产业链环节"


async def industry_for(market, holding):
    try:
        code = ("SH" if holding["stockCode"].startswith("6") else "SZ") + holding["stockCode"]
        payload = (await market.get("https://emweb.securities.eastmoney.com/PC_HSF10/CoreConception/PageAjax",
                                    {"code": code})).json()
        tags = [item["BOARD_NAME"] for item in payload.get("ssbk", [])
                if item.get("BOARD_RANK", 99) <= 3]
        business = next((item.get("MAINPOINT_CONTENT", "") for item in payload.get("hxtc", [])
                         if item.get("KEY_CLASSIF") == "主营业务"), "")[:180]
        return {**holding, "status": "AVAILABLE", "industryTags": tags, "business": business,
                "chainRole": chain_role(" ".join(tags) + " " + business),
                "classificationBasis": "公开行业标签与主营业务关键词，不是模型已核实结论"}
    except Exception:
        return {**holding, "status": "UNAVAILABLE", "industryTags": [], "chainRole": "未能确定产业链环节"}
