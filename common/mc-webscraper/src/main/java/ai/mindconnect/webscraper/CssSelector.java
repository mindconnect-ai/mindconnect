package ai.mindconnect.webscraper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CssSelector extends UrlPattern{
    String selector;

    public CssSelector(String s, String pattern, Mode mode) {
        super(pattern, mode);
        this.selector = s;
    }
}
