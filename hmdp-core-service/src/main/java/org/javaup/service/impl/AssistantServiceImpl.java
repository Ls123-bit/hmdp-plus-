package org.javaup.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.config.AssistantProperties;
import org.javaup.dto.AssistantReplyVO;
import org.javaup.dto.ChatRequestDTO;
import org.javaup.dto.Result;
import org.javaup.entity.Shop;
import org.javaup.entity.ShopType;
import org.javaup.entity.Voucher;
import org.javaup.service.IAssistantService;
import org.javaup.service.IShopService;
import org.javaup.service.IShopTypeService;
import org.javaup.service.IVoucherService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @program: hmdp-plus
 * @description: 智能商户推荐客服实现（新增功能）。整体链路：意图识别（规则）-> 商户候选检索（只读查询，不改动原有业务）-> 开源大模型（Ollama）生成自然语言回复 -> 大模型不可用时自动降级为本地规则回复，保证功能始终可用。
 * @author: hmdp-plus
 **/
@Slf4j
@Service
public class AssistantServiceImpl implements IAssistantService {

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("(?:人均|预算|消费|价格)[^0-9]{0,4}([0-9]{1,4})|([0-9]{1,4})\\s*(?:元|块|RMB|rmb)");

    private static final Pattern CHEAP_PATTERN = Pattern.compile("便宜|实惠|性价比|省钱|经济|平价");

    private static final Pattern SCORE_PATTERN = Pattern.compile("评分|好评|口碑|高分|最好吃|最好");

    private static final Pattern HOT_PATTERN = Pattern.compile("人气|热门|销量|排队|火爆|网红");

    private static final Pattern HOURS_PATTERN = Pattern.compile("营业|开门|关门|打烊|几点");

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("地址|在哪|位置|怎么走");

    private static final Pattern GREETING_PATTERN =
            Pattern.compile("^(你好|您好|hello|hi|嗨|哈喽|在吗|在么|早上好|下午好|晚上好)[!！。~～?？.\\s]*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern HELP_PATTERN =
            Pattern.compile("你能做什么|你都会|功能|怎么用|你是谁|帮助|help", Pattern.CASE_INSENSITIVE);

    private static final Pattern VOUCHER_PATTERN =
            Pattern.compile("优惠券|代金券|秒杀|优惠|折扣|团购|抢券|领券");

    /**
     * 常见口语 -> 商户类型名 的同义词映射
     */
    private static final Map<String, String> TYPE_SYNONYMS = buildTypeSynonyms();

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private AssistantProperties properties;

    private RestClient ollamaClient;

    @PostConstruct
    public void initClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        ollamaClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public Result chat(ChatRequestDTO request) {
        String message = request == null ? null : request.getMessage();
        if (!StringUtils.hasText(message)) {
            return Result.fail("请输入您想咨询的内容，例如：推荐一家评分高的美食店");
        }
        message = message.trim();

        List<ShopType> types = shopTypeService.list();
        Map<Long, String> typeNames = types.stream()
                .filter(t -> t.getName() != null)
                .collect(Collectors.toMap(ShopType::getId, ShopType::getName, (a, b) -> a));
        List<Shop> allShops = shopService.list();

        Analysis analysis = analyze(message, allShops, typeNames);
        List<Shop> candidates = analysis.candidates;

        String ruleReply = buildRuleReply(message, analysis, typeNames);
        String mode = "rule";
        String reply = ruleReply;

        if (properties.isEnabled()) {
            String llmReply = tryOllamaReply(message, request.getHistory(), candidates, analysis, typeNames);
            if (StringUtils.hasText(llmReply)) {
                reply = llmReply;
                mode = "ollama";
            }
        }

        AssistantReplyVO vo = new AssistantReplyVO();
        vo.setReply(reply);
        vo.setMode(mode);
        vo.setIntent(analysis.intent);
        vo.setTypeName(analysis.typeName);
        vo.setShops(candidates.size() > properties.getMaxShops()
                ? candidates.subList(0, properties.getMaxShops()) : candidates);
        return Result.ok(vo);
    }

    @Override
    public Result status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("model", properties.getModel());
        boolean available = false;
        if (properties.isEnabled()) {
            try {
                String body = ollamaClient.get().uri("/api/tags").retrieve().body(String.class);
                JSONArray models = JSONUtil.parseObj(body).getJSONArray("models");
                available = models != null && !models.isEmpty();
            } catch (Exception e) {
                log.debug("Ollama not reachable: {}", e.getMessage());
            }
        }
        data.put("ollamaAvailable", available);
        return Result.ok(data);
    }

    // ==================================================================
    // 意图识别与商户检索（纯规则，稳定可控，大模型故障时也能给出正确推荐）
    // ==================================================================

    private Analysis analyze(String message, List<Shop> allShops, Map<Long, String> typeNames) {
        Analysis a = new Analysis();

        if (GREETING_PATTERN.matcher(message).matches()) {
            a.intent = "greeting";
            a.candidates = sortShops(new ArrayList<>(allShops), "score");
            return a;
        }
        if (HELP_PATTERN.matcher(message).find()) {
            a.intent = "help";
            a.candidates = sortShops(new ArrayList<>(allShops), "score");
            return a;
        }
        if (VOUCHER_PATTERN.matcher(message).find()) {
            a.intent = "voucher";
            a.candidates = sortShops(new ArrayList<>(allShops), "sold");
            return a;
        }

        // 1. 指定商户名（消息中包含完整商户名，或去掉括号门店后缀的主名）
        Shop named = allShops.stream()
                .filter(s -> StringUtils.hasText(s.getName()) && message.contains(s.getName()))
                .findFirst().orElse(null);
        if (named == null) {
            named = allShops.stream()
                    .filter(s -> StringUtils.hasText(s.getName()))
                    .filter(s -> {
                        String core = s.getName().replaceAll("[（(\\[【].*", "").trim();
                        return core.length() >= 3 && message.contains(core);
                    })
                    .findFirst().orElse(null);
        }

        // 2. 商户类型匹配：先看类型名是否出现在消息里，再看同义词
        String matchedTypeName = null;
        for (String tn : typeNames.values()) {
            if (message.contains(tn)) {
                matchedTypeName = tn;
                break;
            }
        }
        if (matchedTypeName == null) {
            for (Map.Entry<String, String> e : TYPE_SYNONYMS.entrySet()) {
                if (message.contains(e.getKey())) {
                    matchedTypeName = e.getValue();
                    break;
                }
            }
        }

        // 3. 商户名关键字（如“火锅”“寿司”，按名称模糊匹配）
        String nameKeyword = extractNameKeyword(message);

        // 4. 商圈过滤
        String area = matchArea(message, allShops);

        // 5. 价格意图
        Integer priceCap = null;
        Matcher pm = PRICE_PATTERN.matcher(message);
        if (pm.find()) {
            String num = pm.group(1) != null ? pm.group(1) : pm.group(2);
            try {
                int v = Integer.parseInt(num);
                if (v >= 10 && v <= 9999) {
                    priceCap = v;
                }
            } catch (NumberFormatException ignore) {
            }
        }
        boolean cheap = CHEAP_PATTERN.matcher(message).find();

        // 6. 排序意图
        String sort = null;
        if (SCORE_PATTERN.matcher(message).find()) {
            sort = "score";
        } else if (HOT_PATTERN.matcher(message).find()) {
            sort = "sold";
        } else if (cheap || priceCap != null) {
            sort = "price";
        }

        if (named != null) {
            a.intent = "shop_info";
            a.candidates = new ArrayList<>();
            a.candidates.add(named);
            return a;
        }

        a.intent = "recommend";
        a.typeName = matchedTypeName;
        a.area = area;
        a.nameKeyword = nameKeyword;
        a.priceCap = priceCap;

        List<Shop> candidates = filterShops(allShops, typeNames, matchedTypeName, nameKeyword, area, priceCap);
        if (candidates.isEmpty() && (area != null || priceCap != null)) {
            // 条件过严时逐级放宽，保证永远有推荐结果
            candidates = filterShops(allShops, typeNames, matchedTypeName, nameKeyword, null, null);
        }
        if (candidates.isEmpty() && nameKeyword != null) {
            candidates = filterShops(allShops, typeNames, matchedTypeName, null, null, null);
        }
        if (candidates.isEmpty() && matchedTypeName != null) {
            candidates = filterShops(allShops, typeNames, matchedTypeName, null, null, null);
        }
        if (candidates.isEmpty()) {
            candidates = sortShops(new ArrayList<>(allShops), sort != null ? sort : "score");
        } else {
            candidates = sortShops(candidates, sort);
        }
        a.candidates = candidates;
        return a;
    }

    private List<Shop> filterShops(List<Shop> allShops, Map<Long, String> typeNames,
                                   String typeName, String nameKeyword, String area, Integer priceCap) {
        Set<Long> typeIds = new LinkedHashSet<>();
        if (typeName != null) {
            typeNames.forEach((id, name) -> {
                if (name.equals(typeName) || name.contains(typeName)) {
                    typeIds.add(id);
                }
            });
        }
        return allShops.stream()
                .filter(s -> typeIds.isEmpty() || typeIds.contains(s.getTypeId()))
                .filter(s -> nameKeyword == null
                        || (StringUtils.hasText(s.getName()) && s.getName().contains(nameKeyword)))
                .filter(s -> area == null
                        || (StringUtils.hasText(s.getArea())
                        && (s.getArea().contains(area) || area.contains(s.getArea()))))
                .filter(s -> priceCap == null || (s.getAvgPrice() != null && s.getAvgPrice() <= priceCap))
                .collect(Collectors.toList());
    }

    private List<Shop> sortShops(List<Shop> list, String sort) {
        Comparator<Shop> byScore = Comparator.comparing((Shop s) -> s.getScore() == null ? 0 : s.getScore()).reversed();
        Comparator<Shop> bySold = Comparator.comparing((Shop s) -> s.getSold() == null ? 0 : s.getSold()).reversed();
        Comparator<Shop> byPrice = Comparator.comparing((Shop s) -> s.getAvgPrice() == null ? Long.MAX_VALUE : s.getAvgPrice());
        List<Shop> sorted = new ArrayList<>(list);
        if ("price".equals(sort)) {
            sorted.sort(byPrice.thenComparing(byScore));
        } else if ("sold".equals(sort)) {
            sorted.sort(bySold.thenComparing(byScore));
        } else {
            sorted.sort(byScore.thenComparing(bySold));
        }
        return sorted;
    }

    private String extractNameKeyword(String message) {
        // 常见品类词，用于消息中没有类型名但提到具体品类时做名称模糊匹配
        String[] keywords = {"火锅", "烤肉", "寿司", "日料", "茶餐厅", "咖啡", "奶茶", "甜品", "蛋糕", "烧烤",
                "串串", "小龙虾", "披萨", "汉堡", "涮锅", "烤鱼"};
        for (String k : keywords) {
            if (message.contains(k)) {
                return k;
            }
        }
        return null;
    }

    private String matchArea(String message, List<Shop> allShops) {
        Set<String> areas = new LinkedHashSet<>();
        for (Shop s : allShops) {
            if (StringUtils.hasText(s.getArea())) {
                areas.add(s.getArea().trim());
            }
        }
        for (String area : areas) {
            if (message.contains(area)) {
                return area;
            }
            // “拱宸桥/上塘”这类商圈名按拆分后的片段匹配
            for (String part : area.split("[/、，,]")) {
                if (part.length() >= 2 && message.contains(part)) {
                    return part;
                }
            }
        }
        return null;
    }

    private static Map<String, String> buildTypeSynonyms() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("美食", "美食");
        m.put("吃", "美食");
        m.put("饭", "美食");
        m.put("餐", "美食");
        m.put("饿", "美食");
        m.put("宵夜", "美食");
        m.put("夜宵", "美食");
        m.put("ktv", "KTV");
        m.put("KTV", "KTV");
        m.put("唱歌", "KTV");
        m.put("唱k", "KTV");
        m.put("理发", "丽人·美发");
        m.put("美发", "丽人·美发");
        m.put("剪头", "丽人·美发");
        m.put("烫头", "丽人·美发");
        m.put("发型", "丽人·美发");
        m.put("健身", "健身运动");
        m.put("运动", "健身运动");
        m.put("瑜伽", "健身运动");
        m.put("游泳", "健身运动");
        m.put("按摩", "按摩·足疗");
        m.put("足疗", "按摩·足疗");
        m.put("spa", "美容SPA");
        m.put("SPA", "美容SPA");
        m.put("美容", "美容SPA");
        m.put("护肤", "美容SPA");
        m.put("遛娃", "亲子游乐");
        m.put("亲子", "亲子游乐");
        m.put("儿童", "亲子游乐");
        m.put("带娃", "亲子游乐");
        m.put("游乐", "亲子游乐");
        m.put("喝酒", "酒吧");
        m.put("小酌", "酒吧");
        m.put("清吧", "酒吧");
        m.put("夜生活", "酒吧");
        m.put("聚会", "轰趴馆");
        m.put("派对", "轰趴馆");
        m.put("轰趴", "轰趴馆");
        m.put("团建", "轰趴馆");
        m.put("美甲", "美睫·美甲");
        m.put("美睫", "美睫·美甲");
        return m;
    }

    // ==================================================================
    // 规则回复（兜底）：大模型不可用时依然给出准确、可读的推荐
    // ==================================================================

    private String buildRuleReply(String message, Analysis a, Map<Long, String> typeNames) {
        StringBuilder sb = new StringBuilder();
        List<Shop> shops = limit(a.candidates);

        switch (a.intent) {
            case "greeting":
                return "您好，我是黑马点评的智能商户推荐助手 小黑 🤖\n"
                        + "您可以问我：“推荐一家评分高的美食店”、“人均80以内的火锅”、“想唱歌去哪玩”，我会为您推荐合适的商户～";
            case "help":
                sb.append("我可以帮您：\n")
                        .append("1. 按品类推荐商户（美食、KTV、健身运动、按摩·足疗、美容SPA 等）\n")
                        .append("2. 按预算推荐（例如“人均100以内”）\n")
                        .append("3. 按评分/人气推荐（例如“评分最高的餐厅”）\n")
                        .append("4. 查询商户的地址、人均、营业时间等信息\n")
                        .append("下面是当前平台上口碑较好的商户，供您参考：");
                break;
            case "voucher":
                sb.append("平台会不定期发放商户优惠券与秒杀活动：进入商户详情页即可查看并抢购该商户的优惠券，秒杀券开抢后按库存先到先得。\n");
                appendVouchers(sb);
                sb.append("以下商户目前口碑不错，值得逛逛：");
                break;
            case "shop_info":
                if (!shops.isEmpty()) {
                    Shop s = shops.get(0);
                    sb.append("为您查询到商户「").append(s.getName()).append("」：\n")
                            .append("类型：").append(typeNames.getOrDefault(s.getTypeId(), "-"))
                            .append("｜评分：").append(scoreText(s))
                            .append("｜人均：").append(s.getAvgPrice() == null ? "-" : s.getAvgPrice()).append("元")
                            .append("\n地址：").append(blankToDash(s.getAddress()))
                            .append("\n营业时间：").append(blankToDash(s.getOpenHours()));
                    sb.append("\n点击下方卡片可查看商户详情、优惠券与探店笔记～");
                    return sb.toString();
                }
                return "没有找到对应的商户，换个说法试试？例如“推荐一家美食店”。";
            default:
                if (StringUtils.hasText(a.typeName)) {
                    sb.append("为您找到 ").append(shops.size()).append(" 家「").append(a.typeName).append("」相关商户");
                } else if (StringUtils.hasText(a.nameKeyword)) {
                    sb.append("为您找到 ").append(shops.size()).append(" 家与「").append(a.nameKeyword).append("」相关的商户");
                } else {
                    sb.append("为您推荐以下口碑不错的商户");
                }
                if (a.priceCap != null) {
                    sb.append("（人均 ").append(a.priceCap).append(" 元以内）");
                } else if (a.area != null) {
                    sb.append("（").append(a.area).append("附近）");
                }
                sb.append("：\n");
                int i = 1;
                for (Shop s : shops) {
                    sb.append(i++).append(". ").append(s.getName()).append("｜评分")
                            .append(scoreText(s)).append("｜人均")
                            .append(s.getAvgPrice() == null ? "-" : s.getAvgPrice()).append("元")
                            .append(StringUtils.hasText(s.getArea()) ? "｜" + s.getArea() : "")
                            .append("\n");
                }
                sb.append("点击下方卡片可查看详情，祝您消费愉快～");
                return sb.toString();
        }

        int i = 1;
        for (Shop s : shops) {
            sb.append("\n").append(i++).append(". ").append(s.getName()).append("｜评分")
                    .append(scoreText(s)).append("｜人均")
                    .append(s.getAvgPrice() == null ? "-" : s.getAvgPrice()).append("元");
        }
        return sb.toString();
    }

    private void appendVouchers(StringBuilder sb) {
        try {
            List<Voucher> vouchers = voucherService.lambdaQuery()
                    .orderByDesc(Voucher::getCreateTime)
                    .last("limit 5")
                    .list();
            if (vouchers == null || vouchers.isEmpty()) {
                return;
            }
            Map<Long, String> shopNames = shopService.list().stream()
                    .collect(Collectors.toMap(Shop::getId, Shop::getName, (x, y) -> x));
            sb.append("近期券活动：\n");
            for (Voucher v : vouchers) {
                sb.append("· ").append(v.getTitle() == null ? "商户优惠券" : v.getTitle());
                if (v.getShopId() != null && shopNames.containsKey(v.getShopId())) {
                    sb.append("（").append(shopNames.get(v.getShopId())).append("）");
                }
                if (v.getPayValue() != null) {
                    sb.append(" 售价").append(v.getPayValue() / 100.0).append("元");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("查询优惠券信息失败，忽略: {}", e.getMessage());
        }
    }

    private List<Shop> limit(List<Shop> candidates) {
        return candidates.size() > properties.getMaxShops()
                ? candidates.subList(0, properties.getMaxShops()) : candidates;
    }

    private String scoreText(Shop s) {
        if (s.getScore() == null) {
            return "-";
        }
        // 库中评分按实际评分*10 存储避免小数
        return String.format("%.1f", s.getScore() / 10.0);
    }

    private String blankToDash(String v) {
        return StringUtils.hasText(v) ? v.trim() : "-";
    }

    // ==================================================================
    // 开源大模型（Ollama）增强回复：带候选商户数据做上下文，避免模型编造
    // ==================================================================

    private String tryOllamaReply(String message, List<Map<String, String>> history,
                                  List<Shop> candidates, Analysis a, Map<Long, String> typeNames) {
        try {
            List<Shop> tops = limit(candidates);
            JSONArray shopJson = new JSONArray();
            for (Shop s : tops) {
                JSONObject o = new JSONObject();
                o.set("name", s.getName());
                o.set("type", typeNames.getOrDefault(s.getTypeId(), "-"));
                o.set("area", s.getArea());
                o.set("address", s.getAddress());
                o.set("avgPrice", s.getAvgPrice());
                o.set("score", scoreText(s));
                o.set("openHours", s.getOpenHours());
                shopJson.set(o);
            }

            StringBuilder system = new StringBuilder();
            system.append("你是“黑马点评”本地生活平台的智能商户推荐客服，名字叫小黑。")
                    .append("你只能依据下面提供的商户真实数据作答，禁止编造任何商户、价格、评分、网址或链接，不要输出 Markdown 和 URL。")
                    .append("请用简体中文、友好口语化地回复，控制在150字以内，可推荐商户并在括号中带上评分和人均，最后引导用户点击商户卡片查看详情。\n")
                    .append("识别到的用户意图：").append(a.intent)
                    .append(a.typeName != null ? "，相关品类：" + a.typeName : "")
                    .append(a.priceCap != null ? "，人均预算上限：" + a.priceCap + "元" : "")
                    .append(a.area != null ? "，期望商圈：" + a.area : "")
                    .append("。\n候选商户数据：").append(shopJson);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", system.toString()));
            if (history != null) {
                for (Map<String, String> h : history) {
                    String role = h.get("role");
                    String content = h.get("content");
                    if (("user".equals(role) || "assistant".equals(role)) && StringUtils.hasText(content)) {
                        messages.add(Map.of("role", role, "content", content));
                    }
                }
            }
            messages.add(Map.of("role", "user", "content", message));

            Map<String, Object> body = new HashMap<>();
            body.put("model", properties.getModel());
            body.put("messages", messages);
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0.4, "num_predict", 512));

            String resp = ollamaClient.post()
                    .uri("/api/chat")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JSONObject root = JSONUtil.parseObj(resp);
            JSONObject msg = root.getJSONObject("message");
            String content = msg == null ? null : msg.getStr("content");
            if (!StringUtils.hasText(content)) {
                return null;
            }
            // 兼容 deepseek-r1 等思考型开源模型：剔除思考过程，只保留正文
            content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
            return StringUtils.hasText(content) ? content : null;
        } catch (Exception e) {
            log.warn("调用 Ollama 生成回复失败，降级为规则回复：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 一次对话分析的中间结果
     */
    private static class Analysis {
        String intent = "recommend";
        String typeName;
        String area;
        String nameKeyword;
        Integer priceCap;
        List<Shop> candidates = new ArrayList<>();
    }
}
