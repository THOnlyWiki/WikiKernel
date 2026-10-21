
        $(function () {
            const WIKI_API_URL = '/api.php';
            const areas = [
                "北京市",
                "天津市",
                "河北省",
                "山西省",
                "内蒙古自治区",
                "辽宁省",
                "吉林省",
                "黑龙江省",
                "上海市",
                "江苏省",
                "浙江省",
                "安徽省",
                "福建省",
                "江西省",
                "山东省",
                "河南省",
                "湖北省",
                "湖南省",
                "广东省",
                "广西壮族自治区",
                "海南省",
                "重庆市",
                "四川省",
                "贵州省",
                "云南省",
                "西藏自治区",
                "陕西省",
                "甘肃省",
                "青海省",
                "宁夏回族自治区",
                "新疆维吾尔自治区",
                "香港特别行政区",
                "澳门特别行政区",
                "台湾省",
                "多地区",
                "海外"
            ];
            const types = [
                "展会类活动",
                "聚会类活动",
                "院校例会类活动",
                "混合型活动",
                "音乐类活动",
                "讲座类活动",
                "茶会类活动",
            ];
            const getFileExtension = (filename = '') => {
                return filename.split('.').pop();
            };
            const filterProvinceName = (fullName = '') => {
                const suffixes = ["省", "自治区", "特别行政区"];

                for (const suffix of suffixes) {
                    if (fullName.endsWith(suffix)) {
                        return fullName.replace(suffix, "");
                    }
                }

                return fullName;
            }
            const isNotEmpty = (element) => {
                return element !== null && element !== "";
            }
            const getElements = (arr) => {
                const list = [];
                for (const area of arr) {
                    list.push(new OO.ui.MenuOptionWidget({
                        data: area,
                        label: area
                    }),)
                }
                return list;
            }
            const getCombos = (arr) => {
                const list = [];
                for (const area of arr) {
                    list.push(new OO.ui.MenuOptionWidget({
                        data: area,
                        label: area
                    }),)
                }
                return list;
            }
            const getAllInputValues = () => {
                let table = document.getElementById("editable-table");
                let rows = table.getElementsByTagName("tr");
                let values = [];

                for (var i = 1; i < rows.length; i++) {
                    let row = rows[i];
                    let inputs = row.getElementsByTagName("input");
                    let rowData = {};

                    for (let j = 0; j < inputs.length; j++) {
                        let input = inputs[j];
                        let className = input.classList[1];
                        rowData[className] = input.value;
                    }
                    values.push(rowData);
                }

                return values;
            }
            const getCsrfToken = async () => {
                const response = await fetch(WIKI_API_URL, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: new URLSearchParams({
                        action: 'query',
                        meta: 'tokens',
                        type: 'csrf',
                        format: 'json'
                    }),
                    credentials: 'include'
                });
                const data = await response.json();
                return data.query.tokens.csrftoken;
            }

            const createPage = async (csrfToken, title, content) => {
                const response = await fetch(WIKI_API_URL, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: new URLSearchParams({
                        action: 'edit',
                        title: title,
                        text: content,
                        token: csrfToken,
                        format: 'json'
                    }),
                    credentials: 'include'
                });
                const data = await response.json();
                return data;
            }

            const getUserInfo = async () => {
                const response = await fetch(WIKI_API_URL, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: new URLSearchParams({
                        action: 'query',
                        meta: 'userinfo',
                        uiprop: 'options|email',
                        format: 'json'
                    }),
                    credentials: 'include'
                });
                const data = await response.json();
                return data.query.userinfo;
            }
            const testNull = (element) => {
                if (element[0]) {
                    return !element[0]["valueName"] !== "";
                } else {
                    return false
                }
            }

            const upload = new OO.ui.SelectFileInputWidget();
            const title = new OO.ui.TextInputWidget();
            const short = new OO.ui.TextInputWidget();
            const host = new OO.ui.TextInputWidget();
            const region = new OO.ui.DropdownWidget({
                label: '请选择活动地区',
                menu: {
                    items: getElements(areas)
                }
            });

            const type = new OO.ui.DropdownWidget({
                label: '请选择活动类型',
                menu: {
                    items: getElements(types)
                }
            });

            const hostAmount = new OO.ui.NumberInputWidget({
                value: 0
            });

            const qq_group = new OO.ui.TextInputWidget({
                showButtons: false
            });

            const bilibili_link = new OO.ui.TextInputWidget();
            const weibo_link = new OO.ui.TextInputWidget();
            const website_link = new OO.ui.TextInputWidget();
            const thmarket_link = new OO.ui.TextInputWidget();

            const tableContainer = $(`<div class="table-container"></div>`);
            tableContainer.html(`
                <table id="editable-table">
                    <thead>
                        <tr>
                            <th>名称</th>
                            <th>日期</th>
                            <th>地点</th>
                            <th>摊位数</th>
                            <th>备注</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td><input class="tableInput valueName" type="text" value=""></td>
                            <td><input class="tableInput valueDate" type="text" value=""></td>
                            <td><input class="tableInput valuePlace" type="text" value=""></td>
                            <td><input class="tableInput valueNumber" type="text" value=""></td>
                            <td><input class="tableInput valueText" type="text" value=""></td>
                        </tr>
                    </tbody>
                </table>`);

            const tableLengths = new OO.ui.NumberInputWidget({
                value: 1,
                min: 0,
                max: 1000,
                step: 1
            });

            const eventPicUploads = new OO.ui.SelectFileInputWidget({
                $tabIndexed: $('<a class="oo-ui-buttonElement-button" role="button" tabindex="0" rel="nofollow"><span class="oo-ui-iconElement-icon oo-ui-iconElement-noIcon"></span><span class="oo-ui-labelElement-label">Select files</span><span class="oo-ui-indicatorElement-indicator oo-ui-indicatorElement-noIndicator"></span><input title="" class="oo-ui-inputWidget-input" type="file" tabindex="-1" multiple="multiple"></a>'),
                multiple: true
            })

            tableLengths.on('change', function (value) {
                const tbody = $('#editable-table tbody');
                const currentRows = tbody.find('tr').length;

                if (value > currentRows) {
                    for (let i = currentRows; i < value; i++) {
                        tbody.append(`
                <tr>
                    <td><input class="tableInput valueName" type="text" value=""></td>
                    <td><input class="tableInput valueDate" type="text" value=""></td>
                    <td><input class="tableInput valuePlace" type="text" value=""></td>
                    <td><input class="tableInput valueNumber" type="text" value=""></td>
                    <td><input class="tableInput valueText" type="text" value=""></td>
                </tr>
            `);
                    }
                } else if (value < currentRows) {
                    tbody.find('tr').slice(value).remove();
                }
            });

            const submit = new OO.ui.ButtonWidget({
                label: '提交',
                flags: [
                    'primary',
                    'progressive'
                ]
            });

            const generate = new OO.ui.ButtonWidget({
                label: '生成',
                flags: [
                    'primary',
                    'progressive'
                ]
            });

            const result = new OO.ui.MultilineTextInputWidget({
                rows: 5,
                value: ''
            });

            submit.on('click', async () => {
                const titleValue = title.getValue();
                const eventPicUploadsValue = eventPicUploads?.currentFiles;
                const content = result.getValue();

                // 检查内容是否为空
                if (content === null || content === '') {
                    OO.ui.alert('请在点击生成代码后再创建页面！').done(function () {
                    });
                    return;
                }

                OO.ui.confirm('请问是否确认要创建此页面?').done(async function (confirmed) {
                    if (confirmed) {
                        try {
                            // 获取 CSRF 令牌
                            let csrfToken = await getCsrfToken();

                            // 获取用户信息
                            let userInfo = await getUserInfo();

                            // 检查登录状态和验证状态
                            if (!userInfo.id || !userInfo.emailauthenticated) {
                                OO.ui.alert('请先登录并验证电子邮件后再进行操作！').done(function () {
                                });
                                return;
                            }

                            // 获取上传的文件
                            const file = upload.currentFiles[0];  // 确保文件存在
                            if (!file) {
                                OO.ui.alert('请先选择一个文件进行上传！').done(function () {
                                });
                                return;
                            }

                            // 创建 FormData 对象
                            const formData = new FormData();
                            formData.append('action', 'upload');
                            formData.append('filename', `${titleValue}.${getFileExtension(upload.currentFiles[0].name)}`); // 上传的文件名称
                            formData.append('file', file); // 实际上传的文件
                            formData.append('token', csrfToken); // CSRF 令牌
                            formData.append('format', 'json'); // 返回的格式为 JSON

                            const response = await fetch(WIKI_API_URL, {
                                method: "POST",
                                body: formData,
                            });

                            if (eventPicUploadsValue?.length > 0) {
                                const uploadPromises = eventPicUploadsValue.map(async (file, index) => {
                                    if (file) {
                                        const newFileName = `${titleValue} 宣传图${index + 1}.${getFileExtension(file.name)}`;

                                        const formDataForBackFile = new FormData();
                                        formDataForBackFile.append('action', 'upload');
                                        formDataForBackFile.append('filename', newFileName);
                                        formDataForBackFile.append('file', file);
                                        formDataForBackFile.append('token', csrfToken);
                                        formDataForBackFile.append('format', 'json');

                                        const responseForBackFile = await fetch(WIKI_API_URL, {
                                            method: "POST",
                                            body: formDataForBackFile,
                                        });
                                        const dataForBackFile = await responseForBackFile.json();
                                        if (dataForBackFile.error) {
                                            OO.ui.alert('返照文件上传失败：' + dataForBackFile.error.info).done(function () {
                                            });
                                        }
                                    }
                                });

                                try {
                                    await Promise.all(uploadPromises);
                                } catch (error) {
                                    OO.ui.alert(error.message).done(function () {
                                    });
                                }
                            }

                            const data = await response.json();
                            if (data.upload && data.upload.result === 'Success') {
                                let result = await createPage(csrfToken, titleValue, content);

                                if (result.edit && result.edit.result === 'Success') {
                                    OO.ui.alert('页面创建成功！').done(function () {
                                        window.open(`https://thonly.cc/index.php?title=${titleValue}`);
                                    });
                                } else {
                                    OO.ui.alert('页面创建失败：' + JSON.stringify(result)).done(function () {
                                    });
                                }
                            } else {
                                OO.ui.alert('文件上传失败：' + JSON.stringify(data.upload)).done(function () {
                                });
                            }
                        } catch (error) {
                            console.error('Error:', error);
                            OO.ui.alert('页面创建时出错：' + error.message).done(function () {
                            });
                        }
                    }
                });
            });

            generate.on('click', () => {
                const titleValue = title.getValue();
                const logoValue = upload?.currentFiles[0]?.name;
                const shortnameValue = short.getValue();
                const hostValue = host.getValue();
                const regionValue = region.getLabel();
                const typeValue = type.getLabel();
                const hostAmountValue = hostAmount.getValue();
                const qqGroupValue = qq_group.getValue();
                const weiboValue = weibo_link.getValue();
                const websiteValue = website_link.getValue();
                const biliValue = bilibili_link.getValue();
                const thmarketValue = thmarket_link.getValue();
                let output = ""
                output += "{{展会}}\n\n";
                //信息集合框
                output += "{{信息集合框\n";
                {
                    if (isNotEmpty(titleValue)) output += `|titlename=${titleValue}\n`;
                    if (isNotEmpty(logoValue)) output += `|图片=${titleValue}.${getFileExtension(logoValue)}\n`;
                    if (isNotEmpty(titleValue)) output += `|活动名称=${titleValue}\n`;
                    if (isNotEmpty(shortnameValue)) output += `|活动别称=${shortnameValue}\n`;
                    if (isNotEmpty(regionValue)) output += `|地区=${regionValue}\n`;
                }
                output += "}}\n\n";
                //活动信息
                output += "{{活动信息\n";
                {
                    if (isNotEmpty(titleValue)) output += `|活动名称=${titleValue}\n`;
                    if (isNotEmpty(shortnameValue)) output += `|活动简称=${shortnameValue}\n`;
                    if (isNotEmpty(hostValue)) output += `|主办方=${hostAmountValue}\n`;
                    if (isNotEmpty(regionValue)) output += `|地区=${regionValue}\n`;
                    if (isNotEmpty(typeValue)) output += `|类型=${typeValue}\n`;
                    if (isNotEmpty(hostAmountValue)) output += `|举办次数=${hostAmountValue}\n`;
                    if (isNotEmpty(qqGroupValue)) output += `|QQ群=${qqGroupValue}\n`;
                    if (isNotEmpty(websiteValue)) output += `|官方网址=${websiteValue}\n`;
                    if (isNotEmpty(biliValue)) output += `|官方哔哩哔哩=${biliValue}\n`;
                    if (isNotEmpty(weiboValue)) output += `|官方微博=${weiboValue}\n`;
                    if (isNotEmpty(thmarketValue)) output += `|东方市场=${thmarketValue}\n`;
                }
                output += "}}\n\n";
                //场地信息
                output += "{{历届信息\n";
                output += "|\n";
                {
                    const allCol = getAllInputValues();
                    const cLength = allCol.length - 1;
                    if (testNull(allCol)) {
                        for (let i = 0; i <= cLength; i++) {
                            output += "{{单届信息\n" +
                                `|[[${titleValue}/${allCol[i]["valueName"]}|${allCol[i]["valueName"]}]]\n` +
                                `|${allCol[i]["valueDate"]}\n` +
                                `|${allCol[i]["valuePlace"]}\n` +
                                `|${allCol[i]["valueNumber"]}\n` +
                                `|${allCol[i]["valueText"]}\n` +
                                `}}\n`
                        }
                    }
                }
                output += "}}\n\n";
                //历届宣传画廊
                const eventPicUploadsValue = eventPicUploads?.currentFiles;
                output += `{{历届宣传画廊|\n`
                output += `=== 第一届至第二届 ===`
                output += `<gallery mode="packed" heights="300px">\n`
                // 动态添加图片文件
                if (eventPicUploadsValue && eventPicUploadsValue.length > 0) {
                    for (let i = 0; i < eventPicUploadsValue.length; i++) {
                        const file = eventPicUploadsValue[i];
                        const fileName = `${titleValue} 宣传图${i + 1}.${getFileExtension(file.name)}`;
                        output += `${fileName}|第${i + 1}届\n`;
                    }
                }
                output += `</gallery>`
                output += `\n}}\n\n`
                //历代LOGO
                output += `== 历代LOGO ==\n`
                output += `<gallery mode="packed" heights="300px"></gallery>`
                output += `\n\n`
                //尾部
                output += `{{页面注释}}`
                if (regionValue !== "请选择活动地区") {
                    const shortRegName = filterProvinceName(regionValue);
                    output += `{{${shortRegName}活动}}\n`;
                }
                result.setValue(output);
            });

            $('.wrapper').append(
                $("<h3>基本信息</h3>"),
                $("<p>活动Logo:</p>"),
                upload.$element,
                $("<p>活动名称/页面标题:</p>"),
                title.$element,
                $("<p>活动缩写:</p>"),
                short.$element,
                $("<p>主办方:</p>"),
                host.$element,
                $("<p>活动类型:</p>"),
                type.$element,
                $("<p>活动地区:</p>"),
                region.$element,
                $("<p>举办次数:</p>"),
                hostAmount.$element,
                $("<h3>社交信息:</h3>"),
                $("<p>QQ群:</p>"),
                qq_group.$element,
                $("<p>哔哩哔哩账号链接:</p>"),
                bilibili_link.$element,
                $("<p>微博账号链接:</p>"),
                weibo_link.$element,
                $("<p>官网链接:</p>"),
                website_link.$element,
                $("<p>东方市场账号链接:</p>"),
                thmarket_link.$element,
                $("<h3>场地信息:</h3>"),
                tableContainer,
                $("<p>表格长度:</p>"),
                tableLengths.$element,
                $("<p>历届宣传图上传:</p>"),
                eventPicUploads.$element,
                $("<p>输出结果:</p>"),
                result.$element,
                $("<br>"),
                submit.$element,
                generate.$element
            );
        });
    