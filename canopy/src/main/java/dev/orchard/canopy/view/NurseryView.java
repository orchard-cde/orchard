package dev.orchard.canopy.view;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.orchard.nursery.SeedlingProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "nursery", layout = MainLayout.class)
@PageTitle("Nursery | Orchard Canopy")
public class NurseryView extends VerticalLayout {

    private final SeedlingProvider seedlingProvider;

    @Autowired
    public NurseryView(SeedlingProvider seedlingProvider) {
        this.seedlingProvider = seedlingProvider;
        setSizeFull();
        setPadding(true);

        add(createHeader());
        add(createProviderStatus());
        add(createMachineSizes());
    }

    private VerticalLayout createHeader() {
        H2 title = new H2("Nursery");
        title.addClassNames(LumoUtility.Margin.NONE);

        Paragraph description = new Paragraph(
            "The nursery is where seedlings (VMs) are grown. " +
            "Configure VM providers and machine templates here."
        );
        description.addClassNames(LumoUtility.TextColor.SECONDARY);

        VerticalLayout header = new VerticalLayout(title, description);
        header.setPadding(false);
        header.setSpacing(false);
        return header;
    }

    private Div createProviderStatus() {
        Div card = new Div();
        card.addClassNames(
            LumoUtility.Background.CONTRAST_5,
            LumoUtility.BorderRadius.MEDIUM,
            LumoUtility.Padding.LARGE,
            LumoUtility.Margin.Top.MEDIUM
        );

        H3 title = new H3("Seedling Provider");
        title.addClassNames(LumoUtility.Margin.NONE, LumoUtility.Margin.Bottom.MEDIUM);

        boolean available = seedlingProvider.isAvailable();

        Div statusLine = new Div();
        Icon statusIcon = available ?
            VaadinIcon.CHECK_CIRCLE.create() :
            VaadinIcon.CLOSE_CIRCLE.create();
        statusIcon.setColor(available ? "var(--lumo-success-color)" : "var(--lumo-error-color)");

        Span providerName = new Span(seedlingProvider.getProviderId());
        providerName.getStyle().set("font-weight", "500").set("margin-left", "0.5rem");

        Span statusText = new Span(available ? " - Available" : " - Not Available");
        statusText.addClassNames(LumoUtility.TextColor.SECONDARY);

        statusLine.add(statusIcon, providerName, statusText);

        card.add(title, statusLine);

        if (!available) {
            Paragraph help = new Paragraph(
                "The QEMU provider requires qemu-system-x86_64 and qemu-img to be installed. " +
                "Install with: sudo apt install qemu-system-x86 qemu-utils"
            );
            help.addClassNames(
                LumoUtility.TextColor.SECONDARY,
                LumoUtility.FontSize.SMALL,
                LumoUtility.Margin.Top.MEDIUM
            );
            card.add(help);
        }

        return card;
    }

    private VerticalLayout createMachineSizes() {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.addClassNames(LumoUtility.Margin.Top.LARGE);

        H3 title = new H3("Machine Sizes");
        title.addClassNames(LumoUtility.Margin.NONE);

        FlexLayout cards = new FlexLayout();
        cards.setWidthFull();
        cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cards.getStyle().set("gap", "1rem");
        cards.addClassNames(LumoUtility.Margin.Top.MEDIUM);

        cards.add(createMachineSizeCard("Small", "2 CPU", "4 GB RAM", "20 GB Disk", "Ideal for small projects"));
        cards.add(createMachineSizeCard("Medium", "4 CPU", "8 GB RAM", "40 GB Disk", "Good for most workloads"));
        cards.add(createMachineSizeCard("Large", "8 CPU", "16 GB RAM", "80 GB Disk", "For resource-intensive tasks"));

        section.add(title, cards);
        return section;
    }

    private Div createMachineSizeCard(String name, String cpu, String memory, String disk, String description) {
        Div card = new Div();
        card.addClassNames(
            LumoUtility.Background.CONTRAST_5,
            LumoUtility.BorderRadius.MEDIUM,
            LumoUtility.Padding.MEDIUM
        );
        card.getStyle()
            .set("min-width", "200px")
            .set("flex", "1");

        Span nameSpan = new Span(name);
        nameSpan.getStyle()
            .set("font-size", "1.1rem")
            .set("font-weight", "bold")
            .set("display", "block");

        Div specs = new Div();
        specs.addClassNames(LumoUtility.Margin.Top.SMALL);
        specs.add(createSpecLine(VaadinIcon.COG, cpu));
        specs.add(createSpecLine(VaadinIcon.DATABASE, memory));
        specs.add(createSpecLine(VaadinIcon.HARDDRIVE, disk));

        Span desc = new Span(description);
        desc.addClassNames(
            LumoUtility.TextColor.SECONDARY,
            LumoUtility.FontSize.SMALL
        );
        desc.getStyle().set("display", "block").set("margin-top", "0.5rem");

        card.add(nameSpan, specs, desc);
        return card;
    }

    private Div createSpecLine(VaadinIcon icon, String text) {
        Div line = new Div();
        line.addClassNames(LumoUtility.FontSize.SMALL);
        line.getStyle().set("margin", "0.25rem 0");

        Icon i = icon.create();
        i.setSize("14px");
        i.getStyle().set("margin-right", "0.5rem");
        i.addClassNames(LumoUtility.TextColor.SECONDARY);

        line.add(i, new Span(text));
        return line;
    }
}
